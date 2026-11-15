-- Esquema do banco do Core API (seção 5 do desenho; fichas CT2 e CT12).
-- Aplicado pelo MySQL só na criação do volume (/docker-entrypoint-initdb.d).
-- Para mudar o esquema num volume existente, use ALTER TABLE.
-- O Hibernate (a partir do C2) só valida este esquema; não cria nem altera tabelas.

CREATE TABLE app_user (
  id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  email            VARCHAR(254) NOT NULL,
  password_hash    VARCHAR(100) NOT NULL,
  ethereum_address VARCHAR(42)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  CONSTRAINT uk_app_user_email   UNIQUE (email),
  CONSTRAINT uk_app_user_address UNIQUE (ethereum_address)
);

CREATE TABLE eth_transaction (
  task_id             CHAR(36)       NOT NULL PRIMARY KEY,   -- UUID do Blockchain Service
  user_id             BIGINT         NOT NULL,
  destination_address VARCHAR(42)    NOT NULL,
  amount_eth          DECIMAL(38,18) NOT NULL,
  status              VARCHAR(16)    NOT NULL,                -- PENDING | CONFIRMED | FAILED
  transaction_hash    VARCHAR(66)    NULL,
  failure_reason      VARCHAR(32)    NULL,                    -- REJECTED_BY_NETWORK | TRANSACTION_REVERTED | INTERNAL_ERROR
  created_at          DATETIME(3)    NOT NULL,
  updated_at          DATETIME(3)    NOT NULL,
  CONSTRAINT fk_eth_transaction_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
