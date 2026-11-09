CREATE TABLE accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_account_balance_nonnegative CHECK (balance >= 0),
    CONSTRAINT chk_account_version_nonnegative CHECK (version >= 0),
    CONSTRAINT chk_account_id_nonblank CHECK (length(btrim(account_id)) > 0)
);

CREATE TABLE balance_transactions (
    transaction_id VARCHAR(100) PRIMARY KEY,
    operation_type VARCHAR(16) NOT NULL,
    source_account_id VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64),
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transaction_type CHECK (operation_type IN ('CREDIT', 'DEBIT', 'TRANSFER')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'REJECTED')),
    CONSTRAINT chk_transaction_destination CHECK (
        (operation_type = 'TRANSFER' AND destination_account_id IS NOT NULL
         AND source_account_id <> destination_account_id)
        OR (operation_type <> 'TRANSFER' AND destination_account_id IS NULL)
    ),
    CONSTRAINT chk_transaction_failure CHECK (
        (status = 'REJECTED' AND failure_code IS NOT NULL)
        OR (status <> 'REJECTED' AND failure_code IS NULL)
    )
);
