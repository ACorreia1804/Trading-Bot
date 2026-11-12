CREATE TABLE candles (
    id          BIGSERIAL PRIMARY KEY,
    symbol      TEXT        NOT NULL,
    intervalo   TEXT        NOT NULL,
    fecho_em    TIMESTAMPTZ NOT NULL,
    abertura    NUMERIC(18,8) NOT NULL,
    maximo      NUMERIC(18,8) NOT NULL,
    minimo      NUMERIC(18,8) NOT NULL,
    fecho       NUMERIC(18,8) NOT NULL,
    volume      NUMERIC(24,8) NOT NULL,
    UNIQUE (symbol, intervalo, fecho_em)
);

CREATE TABLE trades (
    id            BIGSERIAL PRIMARY KEY,
    symbol        TEXT        NOT NULL,
    lado          TEXT        NOT NULL,
    quantidade    NUMERIC(18,8) NOT NULL,
    preco         NUMERIC(18,8) NOT NULL,
    valor_usdt    NUMERIC(18,8) NOT NULL,
    order_id      TEXT,
    ambiente      TEXT        NOT NULL,
    executado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);