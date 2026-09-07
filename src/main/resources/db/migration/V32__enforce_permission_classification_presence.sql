ALTER TABLE access_control.permissions
    DROP CONSTRAINT ck_authorization_permission_classification;

ALTER TABLE access_control.permissions
    ADD CONSTRAINT ck_authorization_permission_classification
        CHECK (
            (
                persona IS NOT NULL
                AND persona IN (
                    'STAFF',
                    'CUSTOMER'
                )
                AND administrative_scope IS NULL
            )
            OR
            (
                persona IS NULL
                AND administrative_scope IS NOT NULL
                AND administrative_scope IN (
                    'PLATFORM',
                    'ORGANIZATION',
                    'TENANT'
                )
            )
        );
