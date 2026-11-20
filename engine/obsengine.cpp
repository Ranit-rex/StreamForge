#include "obsengine.h"

#include <QByteArray>
#include <QDir>
#include <QStandardPaths>

#if STREAMFORGE_HAS_LIBOBS
#include <obs.h>
#endif

ObsEngine::~ObsEngine()
{
    shutdown();
}

bool ObsEngine::initialize()
{
    if (initialized_)
        return true;

#if STREAMFORGE_HAS_LIBOBS
    const QString configPath =
        QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation);
    if (!QDir().mkpath(configPath))
        return false;

    const QByteArray configPathBytes = configPath.toUtf8();
    if (!obs_startup("en-US", configPathBytes.constData(), nullptr))
        return false;

    initialized_ = true;
    return true;
#else
    return false;
#endif
}

void ObsEngine::shutdown()
{
#if STREAMFORGE_HAS_LIBOBS
    if (initialized_)
        obs_shutdown();
#endif
    initialized_ = false;
}

QString ObsEngine::statusMessage() const
{
#if STREAMFORGE_HAS_LIBOBS
    return initialized_ ? QStringLiteral("libobs initialized")
                        : QStringLiteral("libobs initialization unavailable");
#else
    return QStringLiteral("libobs SDK not found — UI preview only");
#endif
}
