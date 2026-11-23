#ifndef PAYHUB_NETWORK_WINSOCKSESSION_H_
#define PAYHUB_NETWORK_WINSOCKSESSION_H_

namespace payhub::network {

// RAII wrapper around WSAStartup / WSACleanup.
// Create exactly one instance before using any socket class.
class WinsockSession {
public:
    WinsockSession();
    ~WinsockSession();

    WinsockSession(const WinsockSession&) = delete;
    WinsockSession& operator=(const WinsockSession&) = delete;
};

} // namespace payhub::network

#endif
