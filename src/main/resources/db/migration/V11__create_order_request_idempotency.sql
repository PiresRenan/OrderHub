-- ---------------------------------------------------------------------------
-- Durable create-Order request idempotency
--
-- The row is created as PROCESSING before any Order ID is generated.
--
-- PROCESSING is transaction-local by design and must never be intentionally
-- committed. A successful create transitions the same row to COMPLETED inside
-- the physical Order/Catalog/Inventory transaction.
--
-- There is intentionally no foreign key to orders.orders:
--
-- - acquisition precedes Order creation;
-- - PROCESSING therefore has no Order ID yet;
-- - the idempotency row and Order still commit or roll back atomically through
--   the application transaction boundary.
-- ---------------------------------------------------------------------------

CREATE TABLE orders.order_request_idempotency (
    tenant_id UUID NOT NULL,
    operation TEXT NOT NULL,
    key_digest BYTEA NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    state TEXT NOT NULL,

    order_id UUID,
    order_status TEXT,
    allocation_outcome TEXT,

    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_order_request_idempotency
        PRIMARY KEY (
            tenant_id,
            operation,
            key_digest
        ),

    CONSTRAINT ck_order_request_idempotency_operation
        CHECK (
            operation = 'CREATE_ORDER_V1'
        ),

    CONSTRAINT ck_order_request_idempotency_key_digest_length
        CHECK (
            octet_length(key_digest) = 32
        ),

    CONSTRAINT ck_order_request_idempotency_request_fingerprint_length
        CHECK (
            octet_length(request_fingerprint) = 32
        ),

    CONSTRAINT ck_order_request_idempotency_state
        CHECK (
            state IN (
                'PROCESSING',
                'COMPLETED'
            )
        ),

    CONSTRAINT ck_order_request_idempotency_order_status
        CHECK (
            order_status IS NULL
            OR order_status = 'CREATED'
        ),

    CONSTRAINT ck_order_request_idempotency_allocation_outcome
        CHECK (
            allocation_outcome IS NULL
            OR allocation_outcome IN (
                'FULLY_ALLOCATED',
                'PARTIALLY_BACKORDERED',
                'FULLY_BACKORDERED'
            )
        ),

    CONSTRAINT ck_order_request_idempotency_completion
        CHECK (
            (
                state = 'PROCESSING'
                AND order_id IS NULL
                AND order_status IS NULL
                AND allocation_outcome IS NULL
                AND completed_at IS NULL
            )
            OR
            (
                state = 'COMPLETED'
                AND order_id IS NOT NULL
                AND order_status IS NOT NULL
                AND allocation_outcome IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
);
