#pragma once

#include <QString>

class ObsEngine final
{
public:
    ObsEngine() = default;
    ~ObsEngine();

    ObsEngine(const ObsEngine &) = delete;
    ObsEngine &operator=(const ObsEngine &) = delete;

    bool initialize();
    void shutdown();
    bool isInitialized() const noexcept { return initialized_; }
    QString statusMessage() const;

private:
    bool initialized_ = false;
};
