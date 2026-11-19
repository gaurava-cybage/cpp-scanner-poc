CREATE TABLE merchant (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE payment (
    id INTEGER PRIMARY KEY,
    merchant_id INTEGER,
    payer TEXT NOT NULL,
    amount DECIMAL,
    status TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchant(id)
);
