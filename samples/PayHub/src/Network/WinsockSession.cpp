#include "WinsockSession.h"

#include <stdexcept>

#include <winsock2.h>

#pragma comment(lib, "ws2_32.lib")

namespace payhub::network {

WinsockSession::WinsockSession() {
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        throw std::runtime_error("WSAStartup failed");
    }
}

WinsockSession::~WinsockSession() {
    WSACleanup();
}

} // namespace payhub::network
