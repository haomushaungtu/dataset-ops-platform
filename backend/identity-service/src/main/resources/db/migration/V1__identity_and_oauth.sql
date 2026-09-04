CREATE TABLE iam_user (
    id varchar(36) PRIMARY KEY,
    username varchar(100) NOT NULL,
    username_normalized varchar(100) NOT NULL,
    password_hash varchar(200) NOT NULL,
    email varchar(254) NOT NULL,
    email_normalized varchar(254) NOT NULL,
    display_name varchar(200) NOT NULL,
    email_verified boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamp with time zone DEFAULT NULL,
    auth_version bigint NOT NULL DEFAULT 1,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by varchar(100) NOT NULL
);

CREATE UNIQUE INDEX uk_iam_user_username_ci ON iam_user (username_normalized);
CREATE UNIQUE INDEX uk_iam_user_email_ci ON iam_user (email_normalized);

CREATE TABLE iam_user_role (
    user_id varchar(36) NOT NULL REFERENCES iam_user(id) ON DELETE CASCADE,
    role_code varchar(64) NOT NULL,
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE iam_audit_event (
    id varchar(36) PRIMARY KEY,
    occurred_at timestamp with time zone NOT NULL,
    actor varchar(200) NOT NULL,
    action varchar(100) NOT NULL,
    target_type varchar(100) NOT NULL,
    target_id varchar(100) NOT NULL,
    outcome varchar(32) NOT NULL
);

CREATE INDEX ix_iam_audit_event_time ON iam_audit_event (occurred_at);
CREATE INDEX ix_iam_audit_event_target ON iam_audit_event (target_type, target_id);

CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL PRIMARY KEY,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp with time zone DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    CONSTRAINT uk_oauth2_registered_client_client_id UNIQUE (client_id)
);

CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamp with time zone DEFAULT NULL,
    authorization_code_expires_at timestamp with time zone DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamp with time zone DEFAULT NULL,
    access_token_expires_at timestamp with time zone DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamp with time zone DEFAULT NULL,
    oidc_id_token_expires_at timestamp with time zone DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamp with time zone DEFAULT NULL,
    refresh_token_expires_at timestamp with time zone DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamp with time zone DEFAULT NULL,
    user_code_expires_at timestamp with time zone DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamp with time zone DEFAULT NULL,
    device_code_expires_at timestamp with time zone DEFAULT NULL,
    device_code_metadata text DEFAULT NULL
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
