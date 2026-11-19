#pragma once

namespace tinypay {

class PaymentStore : public IReceiptPrinter {
public:
    PaymentStore();
    ~PaymentStore();

    long long save(const char* payer, double amount);
    int getStatus(long long paymentId);

    void print(int paymentId) override;

private:
    void* dbConnection;
};

}
