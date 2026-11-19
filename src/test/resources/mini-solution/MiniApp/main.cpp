#include "../MiniCore/PaymentStore.h"
#include "../MiniCore/IReceiptPrinter.h"
#include <cstdio>

using namespace tinypay;

int main(int argc, char** argv) {
    printf("Mini Scanner Fixture\n");

    PaymentStore store;

    long long id = store.save("Alice", 100.50);
    if (id > 0) {
        printf("Saved payment %lld\n", id);
    }

    int status = store.getStatus(id);
    printf("Status: %d\n", status);

    store.print(id);

    return 0;
}
