CREATE TABLE users.external_identity_bindings (
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    user_id UUID NOT NULL,

    CONSTRAINT ck_external_identity_bindings_issuer_byte_length
        CHECK (octet_length(issuer) <= 1024),

    CONSTRAINT ck_external_identity_bindings_subject_byte_length
        CHECK (octet_length(subject) <= 1024),

    CONSTRAINT uq_external_identity_bindings_issuer_subject
        UNIQUE (issuer, subject),

    CONSTRAINT fk_external_identity_bindings_user
        FOREIGN KEY (user_id)
        REFERENCES users.users(id)
);
