CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TYPE account_type AS ENUM ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE');

-- Accounts Table : Defines the Chart of Accounts (COA)
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type account_type NOT NULL,
    currency VARCHAR(3) NOT NULL, -- ISO 4217 Currency Code (e.g., 'GBP')
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tenant (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    available BIGINT NOT NULL DEFAULT 0 CHECK (available >= 0)
);

CREATE TABLE IF NOT EXISTS inbox (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    message_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, message_id)
);

CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL, 
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    amount BIGINT NOT NULL,
    fee BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL, -- CAPTURED | SETTLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    target VARCHAR(64) NOT NULL,
    path VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    body JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

/*
CREATE TABLE payments (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), 
  provider_ref TEXT UNIQUE NOT NULL, 
  tenant TEXT NOT NULL,
  amount BIGINT NOT NULL, 
  fee BIGINT NOT NULL,
  status TEXT NOT NULL, -- CAPTURED | SETTLED
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);     
*/


   
/*
CREATE TABLE payments (
  id UUID PRIMARY KEY,             -- = charge id from the Wallet Service
  amount BIGINT NOT NULL, 
  fee BIGINT NOT NULL,
  status TEXT NOT NULL,                                 -- CAPTURED | SETTLED
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
*/
  
CREATE TABLE payouts (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), 
  idempotency_key TEXT UNIQUE NOT NULL, 
  tenant TEXT NOT NULL,
  amount BIGINT NOT NULL, 
  status TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

