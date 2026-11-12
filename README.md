# Trading Bot (Java)

Bot de trading em Java para BTCUSDT que corre na **testnet da Binance** (dinheiro de teste), com os dados e trades guardados em PostgreSQL. Projeto de aprendizagem em engenharia de dados e cloud: nunca usa dinheiro real.

## Como funciona
- Cruzamento de médias móveis (SMA rápida/lenta) em velas fechadas, com stop-loss de 2%.
- Cada vela fechada vai para a tabela `candles` e cada compra/venda para a tabela `trades`.
- Bot e base de dados correm em containers Docker.

## Stack
Java 17, Maven, ta4j (backtests), Jackson, PostgreSQL 16, Docker Compose.

## Como correr
1. Cria chaves em https://testnet.binance.vision
2. Cria um ficheiro `.env` na raiz (não vai para o git):
   ```
   POSTGRES_PASSWORD=...
   BINANCE_TESTNET_KEY=...
   BINANCE_TESTNET_SECRET=...
   ```
3. Arranca tudo:
   ```
   docker compose up -d --build
   docker logs -f trading-bot
   ```

## Backtests
`Backtest2.java` testa pares de médias (5/20, 10/50, 20/50, 50/200) em velas de 1d, 4h, 1h e 1m (dados da Binance), com 0,15% de custo por operação (fee + slippage), drawdown calculado vela a vela e comparação com buy & hold, divididos em duas metades para ver se o resultado aguenta.

**Conclusão:** nenhuma variante mostrou vantagem consistente sobre buy & hold. Em 1m (5/20) as comissões comem tudo: 243 operações em 5 dias, -54% contra -6% do buy & hold. Em 4h e diário o seguimento de tendência perde menos em mercado em queda, mas fica abaixo do buy & hold no total.

## Aviso
Projeto educativo. Não é aconselhamento financeiro.