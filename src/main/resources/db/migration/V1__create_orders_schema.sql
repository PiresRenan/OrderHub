CREATE SCHEMA orders;

CREATE TABLE orders.orders (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    customer_id UUID NOT NULL,
    status TEXT NOT NULL,

    CONSTRAINT pk_orders
        PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_orders_status
        CHECK (status IN ('CREATED'))
);

CREATE TABLE orders.order_items (
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL,

    CONSTRAINT pk_order_items
        PRIMARY KEY (tenant_id, order_id, line_number),

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders.orders (tenant_id, id)
        ON DELETE CASCADE,

    CONSTRAINT ck_order_items_line_number
        CHECK (line_number >= 0),

    CONSTRAINT ck_order_items_quantity
        CHECK (quantity > 0)
);