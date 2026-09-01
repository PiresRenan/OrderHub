CREATE SCHEMA inventory;

-- ---------------------------------------------------------------------------
-- Tenant inventory policy
-- ---------------------------------------------------------------------------

CREATE TABLE inventory.tenant_policies (
    tenant_id UUID NOT NULL,
    policy TEXT NOT NULL,

    CONSTRAINT pk_inventory_tenant_policies
        PRIMARY KEY (
            tenant_id
        ),

    CONSTRAINT ck_inventory_tenant_policies_policy
        CHECK (
            policy IN (
                'DENY',
                'ALLOW_BACKORDER'
            )
        )
);

-- ---------------------------------------------------------------------------
-- Inventory positions
--
-- One durable stock position exists for each Tenant + sellable Variant.
--
-- available_to_promise remains derived:
--
--     on_hand - committed - safety_stock
--
-- It is intentionally not persisted because it is fully determined by the
-- source-of-truth counters and may legitimately be negative.
-- ---------------------------------------------------------------------------

CREATE TABLE inventory.inventory_positions (
    tenant_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    on_hand BIGINT NOT NULL,
    committed BIGINT NOT NULL,
    backordered BIGINT NOT NULL,
    safety_stock BIGINT NOT NULL,

    CONSTRAINT pk_inventory_positions
        PRIMARY KEY (
            tenant_id,
            variant_id
        ),

    CONSTRAINT ck_inventory_positions_on_hand_non_negative
        CHECK (
            on_hand >= 0
        ),

    CONSTRAINT ck_inventory_positions_committed_non_negative
        CHECK (
            committed >= 0
        ),

    CONSTRAINT ck_inventory_positions_backordered_non_negative
        CHECK (
            backordered >= 0
        ),

    CONSTRAINT ck_inventory_positions_safety_stock_non_negative
        CHECK (
            safety_stock >= 0
        ),

    CONSTRAINT ck_inventory_positions_committed_not_above_on_hand
        CHECK (
            committed <= on_hand
        )
);

-- ---------------------------------------------------------------------------
-- Inventory commitment ledger
--
-- No cross-module foreign keys are introduced:
--
-- - order_id belongs semantically to Orders;
-- - variant_id belongs semantically to Catalog;
-- - tenant_id belongs semantically to Tenants.
--
-- Inventory preserves those opaque identities without coupling its relational
-- schema to another module's tables.
-- ---------------------------------------------------------------------------

CREATE TABLE inventory.inventory_commitments (
    tenant_id UUID NOT NULL,
    commitment_id UUID NOT NULL,
    order_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    requested_quantity BIGINT NOT NULL,
    allocated_quantity BIGINT NOT NULL,
    backordered_quantity BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_inventory_commitments
        PRIMARY KEY (
            tenant_id,
            commitment_id
        ),

    CONSTRAINT uq_inventory_commitments_tenant_order_variant
        UNIQUE (
            tenant_id,
            order_id,
            variant_id
        ),

    CONSTRAINT ck_inventory_commitments_requested_positive
        CHECK (
            requested_quantity > 0
        ),

    CONSTRAINT ck_inventory_commitments_allocated_non_negative
        CHECK (
            allocated_quantity >= 0
        ),

    CONSTRAINT ck_inventory_commitments_backordered_non_negative
        CHECK (
            backordered_quantity >= 0
        ),

    CONSTRAINT ck_inventory_commitments_reconciled
        CHECK (
            allocated_quantity <= requested_quantity
            AND backordered_quantity <= requested_quantity
            AND allocated_quantity =
                requested_quantity - backordered_quantity
        )
);