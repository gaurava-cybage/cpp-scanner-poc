#include "Logger.h"

#include <iostream>

namespace payhub::core {

namespace {
const char* ToString(LogLevel level) {
    switch (level) {
    case LogLevel::Debug: return "DEBUG";
    case LogLevel::Info:  return "INFO";
    case LogLevel::Warn:  return "WARN";
    case LogLevel::Error: return "ERROR";
    }
    return "UNKNOWN";
}
} // namespace

Logger& Logger::Instance() {
    static Logger instance;
    return instance;
}

void Logger::Log(LogLevel level, const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::cout << "[" << ToString(level) << "] " << message << std::endl;
}

} // namespace payhub::core
