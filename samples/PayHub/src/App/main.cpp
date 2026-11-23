// PayHub - Windows console application entry point.
// Wires concrete implementations into core::PaymentHubService and starts the channel TCP server.

#include <cstdint>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <sstream>
#include <string>

#include "HttpRailConnector.h"
#include "KafkaEventPublisher.h"
#include "Logger.h"
#include "LruCache.h"
#include "MigrationRunner.h"
#include "Pacs008Builder.h"
#include "Payment.h"
#include "PaymentHubService.h"
#include "PaymentRouter.h"
#include "PythonSanctionsScreener.h"
#include "SqlitePaymentRepository.h"
#include "SqliteRailConfigRepository.h"
#include "TcpHubServer.h"
#include "WinsockSession.h"

namespace {
struct HubConfig {
    std::string databasePath = "payhub.db";
    std::string migrationsDir = "db\\migrations";
    std::string kafkaBrokers = "localhost:9092";
    std::string railConnectorHost = "localhost";
    std::string railConnectorPort = "8085";
    std::string bankBic = "DEMOINBBXXX";
    std::string screeningScript = "scripts\\sanctions_screen.py";
    std::uint16_t channelPort = 6060;
    std::size_t cacheCapacity = 50000;
};

HubConfig LoadConfigFile(const std::string& filePath) {
    HubConfig config;
    std::ifstream file(filePath);
    if (!file.is_open()) {
        PHUB_LOG_INFO("Config file not found; using defaults.");
        return config;
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string content = buffer.str();

    auto getValue = [&content](const std::string& key) -> std::string {
        size_t keyPos = content.find("\"" + key + "\"");
        if (keyPos == std::string::npos) return "";

        size_t colonPos = content.find(':', keyPos);
        if (colonPos == std::string::npos) return "";

        size_t startPos = content.find_first_not_of(" \t\r\n", colonPos + 1);
        if (startPos == std::string::npos) return "";

        char delimiter = content[startPos];
        if (delimiter != '"' && delimiter != '{') {
            size_t endPos = startPos;
            while (endPos < content.length() && content[endPos] != ',' && content[endPos] != '}') ++endPos;
            return content.substr(startPos, endPos - startPos);
        }

        if (delimiter == '"') {
            startPos++;
            size_t endPos = content.find('"', startPos);
            if (endPos == std::string::npos) return "";
            return content.substr(startPos, endPos - startPos);
        }
        return "";
    };

    std::string dbPath = getValue("databasePath");
    if (!dbPath.empty()) config.databasePath = dbPath;

    std::string migrDir = getValue("migrationsDir");
    if (!migrDir.empty()) config.migrationsDir = migrDir;

    std::string brokers = getValue("kafkaBrokers");
    if (!brokers.empty()) config.kafkaBrokers = brokers;

    std::string host = getValue("railConnectorHost");
    if (!host.empty()) config.railConnectorHost = host;

    std::string port = getValue("railConnectorPort");
    if (!port.empty()) config.railConnectorPort = port;

    std::string bic = getValue("bankBic");
    if (!bic.empty()) config.bankBic = bic;

    std::string script = getValue("screeningScript");
    if (!script.empty()) config.screeningScript = script;

    std::string channelPortStr = getValue("channelPort");
    if (!channelPortStr.empty()) {
        config.channelPort = static_cast<std::uint16_t>(std::atoi(channelPortStr.c_str()));
    }

    std::string capacityStr = getValue("cacheCapacity");
    if (!capacityStr.empty()) {
        config.cacheCapacity = std::strtoul(capacityStr.c_str(), nullptr, 10);
    }

    return config;
}

HubConfig ParseArgs(int argc, char* argv[]) {
    HubConfig config = LoadConfigFile("appsettings.json");
    if (argc > 1) {
        config.channelPort = static_cast<std::uint16_t>(std::atoi(argv[1]));
    }
    return config;
}
} // namespace

int main(int argc, char* argv[]) {
    using namespace payhub;

    try {
        HubConfig config = ParseArgs(argc, argv);
        network::WinsockSession winsock;

        storage::MigrationRunner(config.databasePath, config.migrationsDir).Run();

        storage::SqlitePaymentRepository repository(config.databasePath);
        storage::SqliteRailConfigRepository railConfig(config.databasePath);
        core::PaymentRouter router(railConfig);
        storage::LruCache<std::string, core::Payment> cache(config.cacheCapacity);
        messaging::KafkaEventPublisher publisher(config.kafkaBrokers);
        network::Pacs008Builder pacs008Builder(config.bankBic);
        network::HttpRailConnector railConnector(config.railConnectorHost, config.railConnectorPort, pacs008Builder);
        integration::PythonSanctionsScreener screener(config.screeningScript);

        core::PaymentHubService service(repository, cache, publisher, railConnector, screener, router);

        network::TcpHubServer server(service, config.channelPort);
        server.Run();
    } catch (const std::exception& ex) {
        PHUB_LOG_ERROR(std::string("Fatal: ") + ex.what());
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
