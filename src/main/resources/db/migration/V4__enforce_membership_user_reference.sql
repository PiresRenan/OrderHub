ALTER TABLE users.tenant_memberships
    ADD CONSTRAINT fk_tenant_memberships_user
        FOREIGN KEY (user_id)
        REFERENCES users.users (id);
