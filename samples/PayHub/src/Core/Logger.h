#pragma once
#include <mutex>
#include <string>

#include "PaymentMacros.h"

namespace payhub::core {

enum class LogLevel { Debug, Info, Warn, Error };

// Process-wide singleton logger.
class PAYHUB_EXPORT Logger {
public:
    static Logger& Instance();
    void Log(LogLevel level, const std::string& message);

    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

private:
    Logger() = default;
    std::mutex mutex_;
};

} // namespace payhub::core

#define PHUB_LOG_INFO(msg)  ::payhub::core::Logger::Instance().Log(::payhub::core::LogLevel::Info, (msg))
#define PHUB_LOG_WARN(msg)  ::payhub::core::Logger::Instance().Log(::payhub::core::LogLevel::Warn, (msg))
#define PHUB_LOG_ERROR(msg) ::payhub::core::Logger::Instance().Log(::payhub::core::LogLevel::Error, (msg))
