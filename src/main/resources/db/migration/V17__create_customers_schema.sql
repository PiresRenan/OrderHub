CREATE SCHEMA customers;

CREATE TABLE customers.customer_profiles (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,

    CONSTRAINT pk_customer_profiles
        PRIMARY KEY (
            tenant_id,
            customer_id
        )
);

CREATE TABLE customers.customer_account_bindings (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    user_id UUID NOT NULL,

    CONSTRAINT pk_customer_account_bindings
        PRIMARY KEY (
            tenant_id,
            customer_id,
            user_id
        ),

    CONSTRAINT fk_customer_account_binding_profile_scope
        FOREIGN KEY (
            tenant_id,
            customer_id
        )
        REFERENCES customers.customer_profiles (
            tenant_id,
            customer_id
        )
);

-- Deliberately no foreign keys from customers into users.*, tenants.*,
-- orders.* or any other module schema. Authentication membership and
-- resource ownership remain explicit application/module-boundary concerns.
--
-- No global one-to-one cardinality is imposed between Customer and User.
-- Only the exact (tenant_id, customer_id, user_id) relationship is unique.
