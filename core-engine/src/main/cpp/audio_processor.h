#pragma once

#include <oboe/Oboe.h>
#include <memory>

class AudioProcessor : public oboe::AudioStreamCallback {
public:
    AudioProcessor();
    ~AudioProcessor() override;

    bool init();
    bool start();
    void stop();
    void cleanup();

    // Oboe callback interface
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

    int64_t getCurrentAudioTimestamp();

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    bool mPlaying = false;
    int64_t mSampleCount = 0;
};
