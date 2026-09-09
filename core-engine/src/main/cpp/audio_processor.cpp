#include "audio_processor.h"
#include "logger.h"

AudioProcessor::AudioProcessor() {
    LOGI("AudioProcessor created");
}

AudioProcessor::~AudioProcessor() {
    cleanup();
    LOGI("AudioProcessor destroyed");
}

bool AudioProcessor::init() {
    LOGI("Initializing Audio Engine...");
    
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Audio stream opened. Sample rate: %d, Channels: %d, Format: %d",
         mStream->getSampleRate(), mStream->getChannelCount(), static_cast<int>(mStream->getFormat()));
    return true;
}

bool AudioProcessor::start() {
    if (!mStream) {
        LOGE("Cannot start audio: stream is null");
        return false;
    }
    
    if (mPlaying) return true;

    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
        return false;
    }

    mPlaying = true;
    LOGI("Audio stream started successfully");
    return true;
}

void AudioProcessor::stop() {
    if (!mStream || !mPlaying) return;

    oboe::Result result = mStream->requestStop();
    if (result != oboe::Result::OK) {
        LOGE("Failed to stop audio stream: %s", oboe::convertToText(result));
    } else {
        mPlaying = false;
        LOGI("Audio stream stopped");
    }
}

void AudioProcessor::cleanup() {
    stop();
    if (mStream) {
        mStream->close();
        mStream.reset();
        LOGI("Audio stream closed and reset");
    }
}

oboe::DataCallbackResult AudioProcessor::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames) {
    
    // Fill with silence (zeroes) for the skeleton
    float* floatData = static_cast<float*>(audioData);
    int numChannels = audioStream->getChannelCount();
    
    for (int i = 0; i < numFrames * numChannels; ++i) {
        floatData[i] = 0.0f;
    }

    mSampleCount += numFrames;
    return oboe::DataCallbackResult::Continue;
}

void AudioProcessor::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGW("Oboe stream closed due to error: %s. Re-initializing...", oboe::convertToText(error));
    // In real app, schedule re-init on a background thread
}

int64_t AudioProcessor::getCurrentAudioTimestamp() {
    if (!mStream) return 0;
    
    // Convert current sample count to milliseconds
    int32_t sampleRate = mStream->getSampleRate();
    if (sampleRate == 0) return 0;
    
    return (mSampleCount * 1000) / sampleRate;
}
