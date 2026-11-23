#include <winsock2.h>
#pragma comment(lib, "ws2_32.lib")

namespace tinypay {

int connectToServer(const char* hostname, int port) {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        return -1;
    }

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        WSACleanup();
        return -1;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = inet_addr(hostname);

    int result = connect(sock, (sockaddr*)&addr, sizeof(addr));

    closesocket(sock);
    WSACleanup();

    return result;
}

}
