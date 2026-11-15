#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <lame/lame.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/opt.h>
#include <libavutil/mem.h>
#include <libavutil/audio_fifo.h>
#include <libswresample/swresample.h>

typedef struct {
    JNIEnv *env;
    jobject callback;
    jmethodID update;
    int input;
    FILE *output;
    lame_t encoder;
    AVFormatContext *muxer;
    AVCodecContext *audio_encoder;
    AVAudioFifo *fifo;
    AVPacket *encoded_packet;
    int64_t encoded_samples;
    int sample_rate;
    SwrContext *resampler;
    // The layout the decoder last produced. FFmpeg frees any custom channel map
    // it finds in a destination, so this is zeroed before the first copy.
    AVChannelLayout input_layout;
    int input_rate;
    enum AVSampleFormat input_format;
    int channels;
    int64_t samples;
    int64_t duration_us;
    int scanning;
    int loudness;
    int measuring;
    int pass;
    int passes;
    double gain;
    SwrContext *quantizer;
    SwrContext *peak_resampler;
    double peak;
    AVFilterGraph *meter;
    AVFilterContext *meter_input;
    AVFilterContext *meter_filter;
    AVFilterContext *meter_sink;
    int64_t meter_samples;
    int64_t scanned_samples;
    int64_t first_audible;
    int64_t audible_end;
} Conversion;

static int read_input(void *context, uint8_t *buffer, int size) {
    Conversion *job = context;
    ssize_t count;
    do {
        count = read(job->input, buffer, size);
    } while (count < 0 && errno == EINTR);
    return count > 0 ? (int) count : count == 0 ? AVERROR_EOF : AVERROR(errno);
}

static int64_t seek_input(void *context, int64_t offset, int whence) {
    Conversion *job = context;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) {
        off_t before = lseek(job->input, 0, SEEK_CUR);
        if (before < 0) return AVERROR(errno);
        off_t end = lseek(job->input, 0, SEEK_END);
        if (lseek(job->input, before, SEEK_SET) < 0 || end < 0) return AVERROR(errno);
        return end;
    }
    off_t result = lseek(job->input, offset, whence);
    return result < 0 ? AVERROR(errno) : result;
}

static int report(Conversion *job) {
    double fraction = 0.0;
    if (job->scanned_samples > 0) {
        fraction = (double) job->samples / job->scanned_samples;
    } else if (job->duration_us > 0) {
        fraction = (double) job->samples * AV_TIME_BASE / ((double) job->sample_rate * job->duration_us);
    }
    int percent = job->duration_us <= 0 && job->passes == 1 ? -1
        : (int) ((job->pass + FFMIN(0.99, fraction)) * 100 / job->passes);
    JNIEnv *env = job->env;
    jboolean active = (*env)->CallBooleanMethod(env, job->callback, job->update, percent);
    return (*env)->ExceptionCheck(env) || !active;
}

static int interrupted(void *context) {
    return report(context);
}

static int audible_sample(const uint8_t *plane, int index, enum AVSampleFormat format) {
    // -50 dBFS in each of the PCM formats used by the encoders.
    const double threshold = 0.003162277660168379;
    switch (format) {
        case AV_SAMPLE_FMT_S16: return fabs((double) ((const int16_t *) plane)[index]) >= threshold * 32768.0;
        case AV_SAMPLE_FMT_S32: return fabs((double) ((const int32_t *) plane)[index]) >= threshold * 2147483648.0;
        case AV_SAMPLE_FMT_DBL: return fabs(((const double *) plane)[index]) >= threshold;
        case AV_SAMPLE_FMT_FLT: return fabsf(((const float *) plane)[index]) >= threshold;
        default: return 0;
    }
}

static void scale_sample(uint8_t *plane, int index, enum AVSampleFormat format, double gain) {
    switch (format) {
        case AV_SAMPLE_FMT_S16: ((int16_t *) plane)[index] *= gain; break;
        case AV_SAMPLE_FMT_S32: ((int32_t *) plane)[index] *= gain; break;
        case AV_SAMPLE_FMT_DBL: ((double *) plane)[index] *= gain; break;
        case AV_SAMPLE_FMT_FLT: ((float *) plane)[index] *= gain; break;
        default: break;
    }
}

static int process_samples(Conversion *job, uint8_t *const *planes, int samples,
                           enum AVSampleFormat format, int channels, int *offset) {
    int64_t position = job->samples;
    job->samples += samples;
    *offset = 0;
    if (!job->scanning && !job->audible_end) return samples;

    int planar = av_sample_fmt_is_planar(format);
    enum AVSampleFormat packed = av_get_packed_sample_fmt(format);
    int plane_count = planar ? channels : 1;
    int stride = planar ? 1 : channels;
    if (job->scanning) {
        for (int plane = 0; plane < plane_count; ++plane) {
            for (int i = 0; i < samples * stride; ++i) {
                if (audible_sample(planes[plane], i, packed)) {
                    int64_t absolute = position + i / stride;
                    job->first_audible = FFMIN(job->first_audible, absolute);
                    job->audible_end = FFMAX(job->audible_end, absolute + 1);
                }
            }
        }
        return 0;
    }

    int64_t start = FFMAX(position, job->first_audible);
    int64_t end = FFMIN(job->samples, job->audible_end);
    if (start >= end) return 0;
    *offset = (int) (start - position);
    int64_t fade_length = FFMIN(job->sample_rate, job->audible_end - job->first_audible);
    int64_t fade_start = job->audible_end - fade_length;
    for (int64_t i = FFMAX(start, fade_start); i < end; ++i) {
        double gain = fade_length > 1 ? (double) (job->audible_end - 1 - i) / (fade_length - 1) : 0.0;
        int sample = (int) (i - position) * stride;
        for (int plane = 0; plane < plane_count; ++plane)
            for (int channel = 0; channel < stride; ++channel)
                scale_sample(planes[plane], sample + channel, packed, gain);
    }
    return (int) (end - start);
}

static int write_mp3(Conversion *job, const AVFrame *frame) {
    int count = swr_get_out_samples(job->resampler, frame ? frame->nb_samples : 0);
    if (count <= 0) return count;

    // LAME requires at least 1.25 * samples + 7200 bytes.
    if (count > (INT32_MAX - 7200) / 2) return AVERROR(EINVAL);
    int capacity = count * 2 + 7200;
    int16_t *pcm = av_malloc_array((size_t) count * job->channels, sizeof(*pcm));
    unsigned char *mp3 = av_malloc(capacity);
    int result = AVERROR(ENOMEM);
    if (pcm && mp3) {
        uint8_t *planes[] = { (uint8_t *) pcm };
        int samples = swr_convert(job->resampler, planes, count,
            frame ? (const uint8_t **) frame->extended_data : NULL,
            frame ? frame->nb_samples : 0);
        result = samples;
        if (samples > 0) {
            int offset;
            int retained = process_samples(job, planes, samples, AV_SAMPLE_FMT_S16, job->channels, &offset);
            if (retained > 0) {
                int16_t *start = pcm + (size_t) offset * job->channels;
                int bytes = job->channels == 1
                    ? lame_encode_buffer(job->encoder, start, start, retained, mp3, capacity)
                    : lame_encode_buffer_interleaved(job->encoder, start, retained, mp3, capacity);
                if (bytes < 0 || fwrite(mp3, 1, bytes, job->output) != (size_t) bytes)
                    result = AVERROR(EIO);
            }
        }
    }
    av_free(pcm);
    av_free(mp3);
    return result;
}

// IDs correspond to AudioTarget.nativeId. MP3 (0) retains its separate LAME path.
static const struct {
    const char *muxer;
    const char *encoder;
    enum AVSampleFormat sample_format;
    int bitrate;
} targets[] = {
    [1] = { "ipod", "aac", AV_SAMPLE_FMT_FLTP, 192000 },
    [2] = { "ipod", "alac", AV_SAMPLE_FMT_S32P, 0 },
    [3] = { "ogg", "libopus", AV_SAMPLE_FMT_FLT, 128000 },
    [4] = { "ogg", "libvorbis", AV_SAMPLE_FMT_FLTP, 0 },
    [5] = { "flac", "flac", AV_SAMPLE_FMT_S32, 0 },
    [6] = { "wav", "pcm_s16le", AV_SAMPLE_FMT_S16, 0 },
};

static int open_audio_output(Conversion *job, const char *path, int target, AVCodecContext *decoder) {
    if (target < 1 || target >= (int) (sizeof(targets) / sizeof(targets[0]))) return AVERROR(EINVAL);
    int status = avformat_alloc_output_context2(&job->muxer, NULL, targets[target].muxer, path);
    if (status < 0) return status;
    job->muxer->interrupt_callback = (AVIOInterruptCB) { interrupted, job };
    const AVCodec *codec = avcodec_find_encoder_by_name(targets[target].encoder);
    if (!codec) return AVERROR_ENCODER_NOT_FOUND;
    AVCodecContext *encoder = job->audio_encoder = avcodec_alloc_context3(codec);
    if (!encoder) return AVERROR(ENOMEM);

    int source_rate = decoder->sample_rate > 0 ? decoder->sample_rate : 44100;
    int channels = job->channels;
    int lossless = target == 2 || target == 5 || target == 6;
    if (lossless && decoder->ch_layout.nb_channels > 0 && decoder->ch_layout.nb_channels <= 8)
        channels = decoder->ch_layout.nb_channels;

    const void *configs = NULL;
    int config_count = 0;
    encoder->sample_rate = target == 3 ? 48000 : source_rate;
    if (avcodec_get_supported_config(encoder, codec, AV_CODEC_CONFIG_SAMPLE_RATE, 0,
            &configs, &config_count) >= 0 && config_count > 0) {
        const int *rates = configs;
        int closest = rates[0];
        for (int i = 1; i < config_count; ++i) {
            if (abs(rates[i] - encoder->sample_rate) < abs(closest - encoder->sample_rate))
                closest = rates[i];
        }
        encoder->sample_rate = closest;
    }
    if (target == 4 && (encoder->sample_rate < 8000 || encoder->sample_rate > 192000))
        encoder->sample_rate = 48000;
    job->sample_rate = encoder->sample_rate;
    encoder->time_base = (AVRational) { 1, encoder->sample_rate };
    encoder->sample_fmt = targets[target].sample_format;
    encoder->bit_rate = targets[target].bitrate;
    if (target == 2 || target == 5) {
        int source_bits = decoder->bits_per_raw_sample;
        if (!source_bits) source_bits = decoder->bits_per_coded_sample;
        encoder->bits_per_raw_sample = source_bits > 16 ? 24 : 16;
        if (encoder->bits_per_raw_sample == 16)
            encoder->sample_fmt = target == 2 ? AV_SAMPLE_FMT_S16P : AV_SAMPLE_FMT_S16;
    }
    av_channel_layout_default(&encoder->ch_layout, channels);
    if (lossless && decoder->ch_layout.order == AV_CHANNEL_ORDER_NATIVE &&
        decoder->ch_layout.nb_channels == channels) {
        av_channel_layout_uninit(&encoder->ch_layout);
        status = av_channel_layout_copy(&encoder->ch_layout, &decoder->ch_layout);
        if (status < 0) return status;
    }
    if (avcodec_get_supported_config(encoder, codec, AV_CODEC_CONFIG_CHANNEL_LAYOUT, 0,
            &configs, &config_count) >= 0 && config_count > 0) {
        const AVChannelLayout *layouts = configs;
        const AVChannelLayout *same_count = NULL;
        int matched = 0;
        for (int i = 0; i < config_count; ++i) {
            if (!av_channel_layout_compare(&encoder->ch_layout, &layouts[i])) {
                matched = 1;
                break;
            }
            if (!same_count && layouts[i].nb_channels == channels) same_count = &layouts[i];
        }
        if (!matched) {
            AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
            av_channel_layout_uninit(&encoder->ch_layout);
            status = av_channel_layout_copy(&encoder->ch_layout, same_count ? same_count : &stereo);
            if (status < 0) return status;
        }
    }
    if (target == 4) {
        encoder->flags |= AV_CODEC_FLAG_QSCALE;
        encoder->global_quality = 4 * FF_QP2LAMBDA;
    }
    if (job->muxer->oformat->flags & AVFMT_GLOBALHEADER) encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if ((status = avcodec_open2(encoder, codec, NULL)) < 0) return status;

    job->fifo = av_audio_fifo_alloc(encoder->sample_fmt, encoder->ch_layout.nb_channels, 1);
    job->encoded_packet = av_packet_alloc();
    AVStream *stream = avformat_new_stream(job->muxer, NULL);
    if (!job->fifo || !job->encoded_packet || !stream) return AVERROR(ENOMEM);
    stream->time_base = encoder->time_base;
    if ((status = avcodec_parameters_from_context(stream->codecpar, encoder)) < 0) return status;
    if ((status = avio_open2(&job->muxer->pb, path, AVIO_FLAG_WRITE,
            &job->muxer->interrupt_callback, NULL)) < 0) return status;
    return avformat_write_header(job->muxer, NULL);
}

static int encode_audio_frame(Conversion *job, AVFrame *frame) {
    AVCodecContext *encoder = job->audio_encoder;
    int status = avcodec_send_frame(encoder, frame);
    if (status < 0) return status;
    AVPacket *packet = job->encoded_packet;
    AVStream *stream = job->muxer->streams[0];
    while ((status = avcodec_receive_packet(encoder, packet)) >= 0) {
        av_packet_rescale_ts(packet, encoder->time_base, stream->time_base);
        packet->stream_index = stream->index;
        status = av_interleaved_write_frame(job->muxer, packet);
        if (status < 0) return status;
    }
    return status == AVERROR(EAGAIN) || status == AVERROR_EOF ? 0 : status;
}

static AVFrame *alloc_samples(AVCodecContext *encoder, int samples) {
    AVFrame *frame = av_frame_alloc();
    if (!frame) return NULL;
    frame->nb_samples = samples;
    frame->format = encoder->sample_fmt;
    frame->sample_rate = encoder->sample_rate;
    if (av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout) < 0 ||
        av_frame_get_buffer(frame, 0) < 0) av_frame_free(&frame);
    return frame;
}

static int drain_samples(Conversion *job, int finishing) {
    AVCodecContext *encoder = job->audio_encoder;
    int block = encoder->frame_size > 0 ? encoder->frame_size : 4096;
    int size;
    while ((size = av_audio_fifo_size(job->fifo)) >= block || (finishing && size > 0)) {
        if (report(job)) return AVERROR_EXIT;
        int count = FFMIN(block, size);
        AVFrame *frame = alloc_samples(encoder, count);
        if (!frame) return AVERROR(ENOMEM);
        int read = av_audio_fifo_read(job->fifo, (void **) frame->extended_data, count);
        frame->pts = job->encoded_samples;
        int status = read == count ? encode_audio_frame(job, frame) : AVERROR(EIO);
        av_frame_free(&frame);
        if (status < 0) return status;
        job->encoded_samples += count;
    }
    return 0;
}

static int output_layout(Conversion *job, AVChannelLayout *layout) {
    if (job->audio_encoder) return av_channel_layout_copy(layout, &job->audio_encoder->ch_layout);
    av_channel_layout_default(layout, job->channels);
    return 0;
}

static int open_meter(Conversion *job) {
    job->meter = avfilter_graph_alloc();
    if (!job->meter) return AVERROR(ENOMEM);
    job->meter->nb_threads = 1;
    AVChannelLayout layout = {0};
    int status = output_layout(job, &layout);
    if (status < 0) return status;
    char description[256];
    status = av_channel_layout_describe(&layout, description, sizeof(description));
    av_channel_layout_uninit(&layout);
    if (status < 0 || (size_t) status >= sizeof(description)) return AVERROR(EINVAL);
    char args[512];
    snprintf(args, sizeof(args), "sample_rate=%d:sample_fmt=dbl:channel_layout=%s:time_base=1/%d",
        job->sample_rate, description, job->sample_rate);
    if ((status = avfilter_graph_create_filter(&job->meter_input, avfilter_get_by_name("abuffer"),
            "input", args, NULL, job->meter)) < 0 ||
        (status = avfilter_graph_create_filter(&job->meter_filter, avfilter_get_by_name("ebur128"),
            "loudness", "framelog=verbose", NULL, job->meter)) < 0 ||
        (status = avfilter_graph_create_filter(&job->meter_sink, avfilter_get_by_name("abuffersink"),
            "output", NULL, NULL, job->meter)) < 0 ||
        (status = avfilter_link(job->meter_input, 0, job->meter_filter, 0)) < 0 ||
        (status = avfilter_link(job->meter_filter, 0, job->meter_sink, 0)) < 0)
        return status;
    return avfilter_graph_config(job->meter, NULL);
}

static void update_peak(Conversion *job, const double *samples, size_t count) {
    for (size_t i = 0; i < count; ++i) job->peak = FFMAX(job->peak, fabs(samples[i]));
}

static int measure_peak(Conversion *job, const AVFrame *frame) {
    AVChannelLayout layout = {0};
    int status = output_layout(job, &layout);
    if (status < 0) return status;
    int channels = layout.nb_channels;
    if (!job->peak_resampler) {
        // At least 192 kHz, with oversampling also for high-rate input.
        int rate = FFMAX(192000, job->sample_rate * 2);
        status = swr_alloc_set_opts2(&job->peak_resampler, &layout, AV_SAMPLE_FMT_DBL, rate,
            &layout, AV_SAMPLE_FMT_DBL, job->sample_rate, 0, NULL);
        if (status >= 0) status = swr_init(job->peak_resampler);
    }
    av_channel_layout_uninit(&layout);
    if (status < 0) return status;

    // Pad the peak meter only to account for interpolation at the end of the audio.
    int frames = frame ? frame->nb_samples : 64;
    double *silence = frame ? NULL : av_calloc((size_t) frames * channels, sizeof(double));
    if (!frame && !silence) return AVERROR(ENOMEM);
    const uint8_t *input[] = { frame ? frame->data[0] : (const uint8_t *) silence };
    update_peak(job, (const double *) input[0], (size_t) frames * channels);
    int capacity = swr_get_out_samples(job->peak_resampler, frames);
    if (capacity <= 0) {
        av_free(silence);
        return capacity;
    }
    double *interpolated = av_malloc_array((size_t) capacity * channels, sizeof(double));
    if (!interpolated) {
        av_free(silence);
        return AVERROR(ENOMEM);
    }
    uint8_t *output[] = { (uint8_t *) interpolated };
    status = swr_convert(job->peak_resampler, output, capacity, input, frames);
    if (status > 0) update_peak(job, interpolated, (size_t) status * channels);
    av_free(interpolated);
    av_free(silence);
    return status;
}

static int measure_pcm(Conversion *job, AVFrame *frame) {
    int status = measure_peak(job, frame);
    if (status < 0) return status;
    if (frame) {
        frame->pts = job->meter_samples;
        job->meter_samples += frame->nb_samples;
    }
    status = av_buffersrc_add_frame_flags(job->meter_input, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
    if (status < 0) return status;
    AVFrame *discard = av_frame_alloc();
    if (!discard) return AVERROR(ENOMEM);
    while ((status = av_buffersink_get_frame(job->meter_sink, discard)) >= 0)
        av_frame_unref(discard);
    av_frame_free(&discard);
    return status == AVERROR(EAGAIN) || status == AVERROR_EOF ? 0 : status;
}

static int encode_normalized_pcm(Conversion *job, AVFrame *frame) {
    AVCodecContext *encoder = job->audio_encoder;
    int count = frame->nb_samples;
    enum AVSampleFormat format = encoder ? encoder->sample_fmt : AV_SAMPLE_FMT_S16;
    if (!job->quantizer) {
        int status = swr_alloc_set_opts2(&job->quantizer, &frame->ch_layout, format, job->sample_rate,
            &frame->ch_layout, AV_SAMPLE_FMT_DBL, job->sample_rate, 0, NULL);
        if (status < 0 || (status = swr_init(job->quantizer)) < 0) return status;
    }
    double *samples = (double *) frame->data[0];
    for (int i = 0; i < count * frame->ch_layout.nb_channels; ++i) samples[i] *= job->gain;

    AVFrame *output = av_frame_alloc();
    if (!output) return AVERROR(ENOMEM);
    output->format = format;
    output->sample_rate = job->sample_rate;
    output->nb_samples = count;
    int status = av_channel_layout_copy(&output->ch_layout, &frame->ch_layout);
    if (status < 0 || (status = av_frame_get_buffer(output, 0)) < 0) goto finish;
    status = swr_convert(job->quantizer, output->extended_data, count,
        (const uint8_t **) frame->extended_data, count);
    if (status < 0) goto finish;
    if (status != count) {
        status = AVERROR(EIO);
        goto finish;
    }
    if (encoder) {
        status = av_audio_fifo_write(job->fifo, (void **) output->extended_data, count) == count
            ? drain_samples(job, 0) : AVERROR(EIO);
    } else {
        if (count > (INT32_MAX - 7200) / 2) {
            status = AVERROR(EINVAL);
            goto finish;
        }
        int capacity = count * 2 + 7200;
        unsigned char *mp3 = av_malloc(capacity);
        if (!mp3) {
            status = AVERROR(ENOMEM);
            goto finish;
        }
        int16_t *pcm = (int16_t *) output->data[0];
        int bytes = job->channels == 1
            ? lame_encode_buffer(job->encoder, pcm, pcm, count, mp3, capacity)
            : lame_encode_buffer_interleaved(job->encoder, pcm, count, mp3, capacity);
        status = bytes >= 0 && fwrite(mp3, 1, bytes, job->output) == (size_t) bytes ? 0 : AVERROR(EIO);
        av_free(mp3);
    }

finish:
    av_frame_free(&output);
    return status;
}

static int write_normalized_audio(Conversion *job, const AVFrame *source) {
    int count = swr_get_out_samples(job->resampler, source ? source->nb_samples : 0);
    if (count <= 0) return count;
    AVFrame *pcm = av_frame_alloc();
    if (!pcm) return AVERROR(ENOMEM);
    pcm->format = AV_SAMPLE_FMT_DBL;
    pcm->sample_rate = job->sample_rate;
    pcm->nb_samples = count;
    int status = output_layout(job, &pcm->ch_layout);
    if (status < 0 || (status = av_frame_get_buffer(pcm, 0)) < 0) goto finish;
    count = swr_convert(job->resampler, pcm->extended_data, count,
        source ? (const uint8_t **) source->extended_data : NULL, source ? source->nb_samples : 0);
    status = count;
    if (count > 0) {
        int offset;
        int channels = pcm->ch_layout.nb_channels;
        pcm->nb_samples = process_samples(job, pcm->extended_data, count, AV_SAMPLE_FMT_DBL, channels, &offset);
        pcm->data[0] += (size_t) offset * channels * sizeof(double);
        if (pcm->nb_samples > 0) {
            status = job->measuring ? measure_pcm(job, pcm) : encode_normalized_pcm(job, pcm);
            if (status >= 0) status = count;
        }
    }

finish:
    av_frame_free(&pcm);
    return status;
}

static int write_audio(Conversion *job, const AVFrame *frame) {
    if (job->loudness) return write_normalized_audio(job, frame);
    if (!job->audio_encoder) return write_mp3(job, frame);
    int count = swr_get_out_samples(job->resampler, frame ? frame->nb_samples : 0);
    if (count <= 0) return count;
    AVFrame *converted = alloc_samples(job->audio_encoder, count);
    if (!converted) return AVERROR(ENOMEM);
    int samples = swr_convert(job->resampler, converted->extended_data, count,
        frame ? (const uint8_t **) frame->extended_data : NULL, frame ? frame->nb_samples : 0);
    int status = samples;
    if (samples > 0) {
        enum AVSampleFormat format = converted->format;
        int channels = converted->ch_layout.nb_channels;
        int offset;
        int retained = process_samples(job, converted->extended_data, samples, format, channels, &offset);
        if (retained > 0) {
            int planar = av_sample_fmt_is_planar(format);
            int plane_count = planar ? channels : 1;
            size_t bytes = (size_t) offset * av_get_bytes_per_sample(format) * (planar ? 1 : channels);
            void *planes[plane_count];
            for (int i = 0; i < plane_count; ++i) planes[i] = converted->extended_data[i] + bytes;
            if (av_audio_fifo_write(job->fifo, planes, retained) != retained) status = AVERROR(EIO);
            else {
                int drained = drain_samples(job, 0);
                if (drained < 0) status = drained;
            }
        }
    }
    av_frame_free(&converted);
    return status;
}

static int flush_resampler(Conversion *job) {
    int remaining;
    do {
        if (report(job)) return AVERROR_EXIT;
        remaining = write_audio(job, NULL);
    } while (remaining > 0);
    return remaining;
}

static int configure_resampler(Conversion *job, const AVFrame *frame) {
    if (job->resampler) {
        // A change of rate, format, or layout mid-stream rebuilds the resampler.
        // The output format and the sample timeline stay fixed across the rebuild.
        if (job->input_rate == frame->sample_rate && job->input_format == frame->format &&
            av_channel_layout_compare(&job->input_layout, &frame->ch_layout) == 0)
            return 0;
        int status = flush_resampler(job);
        if (status < 0) return status;
        swr_free(&job->resampler);
    }

    AVChannelLayout output = {0};
    AVCodecContext *encoder = job->audio_encoder;
    int status = output_layout(job, &output);
    if (status >= 0)
        status = swr_alloc_set_opts2(&job->resampler, &output,
            job->loudness ? AV_SAMPLE_FMT_DBL : (encoder ? encoder->sample_fmt : AV_SAMPLE_FMT_S16),
            job->sample_rate, &frame->ch_layout, frame->format, frame->sample_rate, 0, NULL);
    av_channel_layout_uninit(&output);
    if (status < 0 || (status = swr_init(job->resampler)) < 0) return status;
    // The destination holds the layout the decoder produced. It is zeroed here so
    // the copy never frees an uninitialised channel map.
    av_channel_layout_uninit(&job->input_layout);
    status = av_channel_layout_copy(&job->input_layout, &frame->ch_layout);
    if (status < 0) return status;
    job->input_format = frame->format;
    job->input_rate = frame->sample_rate;
    return 0;
}

static int decode(Conversion *job, AVCodecContext *decoder, AVPacket *packet, AVFrame *frame) {
    int status = avcodec_send_packet(decoder, packet);
    if (status < 0) return status;
    while ((status = avcodec_receive_frame(decoder, frame)) >= 0) {
        status = configure_resampler(job, frame);
        if (status >= 0) status = write_audio(job, frame);
        av_frame_unref(frame);
        if (status < 0) return status;
        if (report(job)) return AVERROR_EXIT;
    }
    return status == AVERROR(EAGAIN) || status == AVERROR_EOF ? 0 : status;
}

typedef struct {
    AVFormatContext *format;
    AVIOContext *io;
    AVCodecContext *decoder;
    int index;
} AudioSource;

static void close_source(AudioSource *source) {
    avcodec_free_context(&source->decoder);
    avformat_close_input(&source->format);
    if (source->io) av_freep(&source->io->buffer);
    avio_context_free(&source->io);
}

static int open_source(Conversion *job, AudioSource *source) {
    source->format = avformat_alloc_context();
    if (!source->format) return 4;
    uint8_t *buffer = av_malloc(65536);
    if (!buffer) return 4;
    source->io = avio_alloc_context(buffer, 65536, 0, job, read_input, NULL, seek_input);
    if (!source->io) {
        av_free(buffer);
        return 4;
    }
    source->format->pb = source->io;
    source->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    source->format->interrupt_callback = (AVIOInterruptCB) { interrupted, job };
    if (avformat_open_input(&source->format, NULL, NULL, NULL) < 0) return 1;
    AVFormatContext *format = source->format;
    // Some containers need stream probing before exposing codec parameters.
    avformat_find_stream_info(format, NULL);
    source->index = -1;
    for (unsigned i = 0; i < format->nb_streams; ++i) {
        if (format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            source->index = (int) i;
            break;
        }
    }
    if (source->index < 0) return 2;
    AVStream *stream = format->streams[source->index];
    job->duration_us = stream->duration > 0
        ? av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q)
        : format->duration;
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) return 3;
    source->decoder = avcodec_alloc_context3(codec);
    if (!source->decoder || avcodec_parameters_to_context(source->decoder, stream->codecpar) < 0 ||
        avcodec_open2(source->decoder, codec, NULL) < 0) return 3;
    return 0;
}

static int read_audio(Conversion *job, const AudioSource *source, AVPacket *packet, AVFrame *frame) {
    int status = AVERROR_EXIT;
    while (!report(job) && (status = av_read_frame(source->format, packet)) >= 0) {
        int decoded = packet->stream_index == source->index ? decode(job, source->decoder, packet, frame) : 0;
        av_packet_unref(packet);
        if (decoded < 0) {
            if (decoded != AVERROR_EXIT)
                __android_log_print(ANDROID_LOG_WARN, "VideoConverter", "Audio decode failed: %s", av_err2str(decoded));
            return 4;
        }
    }
    if (report(job)) return 4;
    int flushed = status == AVERROR_EOF ? decode(job, source->decoder, NULL, frame) : status;
    if (flushed < 0 || !job->resampler) {
        __android_log_print(ANDROID_LOG_WARN, "VideoConverter", "Audio read/flush failed: %s, resampler=%d",
            av_err2str(flushed), job->resampler != NULL);
        return 4;
    }
    if (flush_resampler(job) < 0 || !job->samples) return 4;
    return 0;
}

static int convert(JNIEnv *env, jint fd, jstring output, jint target, jint bitrate,
                   int trim_silence, int loudness, jobject callback) {
    Conversion job = {
        .env = env, .callback = callback, .input = fd, .sample_rate = 44100,
        .scanning = trim_silence, .first_audible = INT64_MAX,
        .loudness = loudness, .passes = 1 + !!trim_silence + !!loudness, .gain = 1.0,
        .input_layout = { 0 },
    };
    jclass cls = (*env)->GetObjectClass(env, callback);
    job.update = (*env)->GetMethodID(env, cls, "update", "(I)Z");
    if (!job.update) return 4;
    AudioSource source = {0};
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    off_t input_start = job.passes > 1 ? lseek(fd, 0, SEEK_CUR) : 0;
    if (input_start < 0) return 4;
    int result = open_source(&job, &source);
    if (result != 0) goto finish;
    if (!output) {
        result = report(&job) ? 4 : 0;
        goto finish;
    }
    result = 4;
    AVCodecContext *decoder = source.decoder;
    job.channels = decoder->ch_layout.nb_channels == 1 ? 1 : 2;
    const char *path = (*env)->GetStringUTFChars(env, output, NULL);
    if (!path) goto finish;
    int opened;
    if (target == 0) {
        job.encoder = lame_init();
        opened = AVERROR(ENOMEM);
        if (job.encoder) {
            lame_set_num_channels(job.encoder, job.channels);
            lame_set_in_samplerate(job.encoder, job.sample_rate);
            lame_set_out_samplerate(job.encoder, job.sample_rate);
            lame_set_brate(job.encoder, bitrate);
            lame_set_bWriteVbrTag(job.encoder, 0);
            opened = lame_init_params(job.encoder);
            if (opened >= 0) {
                job.output = fopen(path, "wb");
                if (!job.output) opened = AVERROR(errno);
            }
        }
    } else opened = open_audio_output(&job, path, target, decoder);
    (*env)->ReleaseStringUTFChars(env, output, path);
    if (opened < 0) {
        __android_log_print(ANDROID_LOG_WARN, "VideoConverter", "Could not open encoder %d: %s",
            target, av_err2str(opened));
        goto finish;
    }
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) goto finish;

    if (job.scanning) {
        if (read_audio(&job, &source, packet, frame) != 0) goto finish;
        job.scanned_samples = job.samples;
        job.samples = 0;
        job.scanning = 0;
        ++job.pass;
        // Reopen from the original input position so decoder priming and resampling match the scan.
        swr_free(&job.resampler);
        close_source(&source);
        if (report(&job) || lseek(fd, input_start, SEEK_SET) < 0) goto finish;
        if (open_source(&job, &source) != 0) goto finish;
    }
    if (job.loudness) {
        job.measuring = 1;
        if (open_meter(&job) < 0 || read_audio(&job, &source, packet, frame) ||
            measure_pcm(&job, NULL) < 0) goto finish;
        double integrated;
        if (av_opt_get_double(job.meter_filter, "integrated", AV_OPT_SEARCH_CHILDREN, &integrated) < 0)
            goto finish;
        int measurable = isfinite(integrated) && integrated > -70.0 &&
            job.meter_samples >= (int64_t) job.sample_rate * 4 / 10;
        double gain_db = measurable ? job.loudness - integrated : 0.0;
        if (job.peak > 0.0) gain_db = FFMIN(gain_db, -1.0 - 20.0 * log10(job.peak));
        job.gain = pow(10.0, gain_db / 20.0);
        avfilter_graph_free(&job.meter);
        swr_free(&job.peak_resampler);
        job.scanned_samples = job.samples;
        job.samples = 0;
        job.measuring = 0;
        ++job.pass;
        swr_free(&job.resampler);
        close_source(&source);
        if (report(&job) || lseek(fd, input_start, SEEK_SET) < 0 || open_source(&job, &source) != 0)
            goto finish;
    }
    if (read_audio(&job, &source, packet, frame) != 0 ||
        (job.scanned_samples && job.samples != job.scanned_samples)) goto finish;
    if (job.audio_encoder) {
        if (drain_samples(&job, 1) < 0 || encode_audio_frame(&job, NULL) < 0 ||
            av_write_trailer(job.muxer) < 0 || job.muxer->pb->error < 0) goto finish;
    } else {
        unsigned char ending[7200];
        int bytes = lame_encode_flush(job.encoder, ending, sizeof(ending));
        if (bytes < 0 || fwrite(ending, 1, bytes, job.output) != (size_t) bytes) goto finish;
    }
    result = 0;

finish:
    if (job.output && fclose(job.output) != 0 && result == 0) result = 4;
    if (job.encoder) lame_close(job.encoder);
    if (job.muxer) {
        if (avio_closep(&job.muxer->pb) < 0 && result == 0) result = 4;
        avformat_free_context(job.muxer);
    }
    av_packet_free(&job.encoded_packet);
    av_audio_fifo_free(job.fifo);
    avcodec_free_context(&job.audio_encoder);
    swr_free(&job.resampler);
    av_channel_layout_uninit(&job.input_layout);
    swr_free(&job.quantizer);
    swr_free(&job.peak_resampler);
    avfilter_graph_free(&job.meter);
    av_frame_free(&frame);
    av_packet_free(&packet);
    close_source(&source);
    return result;
}

JNIEXPORT jint JNICALL Java_org_videoconverter_app_NativeConverter_encodeAudio(
    JNIEnv *env, jobject self, jint fd, jstring output, jint target, jint bitrate, jboolean trim_silence, jint loudness, jobject callback) {
    (void) self;
    return convert(env, fd, output, target, bitrate, trim_silence, loudness, callback);
}

JNIEXPORT jboolean JNICALL Java_org_videoconverter_app_NativeConverter_canConvert(
    JNIEnv *env, jobject self, jint fd, jobject callback) {
    (void) self;
    return convert(env, fd, NULL, 0, 0, 0, 0, callback) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_org_videoconverter_app_NativeConverter_setNonblocking(
    JNIEnv *env, jobject self, jint fd) {
    (void) env;
    (void) self;
    int flags;
    do {
        flags = fcntl(fd, F_GETFL);
    } while (flags < 0 && errno == EINTR);
    if (flags < 0) return errno;

    int result;
    do {
        result = fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? errno : 0;
}
