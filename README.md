# Trading Bot (Java)

Bot de trading em Java para BTCUSDT que corre na **testnet da Binance** (dinheiro de teste), com velas e trades guardados em PostgreSQL e um dashboard em Grafana. Projeto de aprendizagem em engenharia de dados: nunca usa dinheiro real.

## Como funciona
- Estratégia: cruzamento de médias móveis (SMA 20/50) em velas fechadas de **4h**, com stop-loss de 2% (verificado ao minuto).
- Cada vela fechada vai para a tabela `candles` e cada compra/venda para a tabela `trades`.
- No arranque, o bot faz **backfill** dos últimos 90 dias de velas, para preencher buracos (por exemplo, quando o PC esteve desligado). Velas repetidas são ignoradas.
- Bot, base de dados e Grafana correm em containers Docker (Docker Compose).

## Stack
Java 17, Maven, ta4j (backtests), Jackson, PostgreSQL 16, Grafana, Docker Compose.

## Como correr
1. Cria chaves em https://testnet.binance.vision
2. Cria um ficheiro `.env` na raiz (não vai para o git):
   ```
   POSTGRES_PASSWORD=...
   GRAFANA_PASSWORD=...
   BINANCE_TESTNET_KEY=...
   BINANCE_TESTNET_SECRET=...
   ```
3. Arranca tudo:
   ```
   docker compose up -d --build
   docker logs -f trading-bot
   ```
4. Abre o Grafana em http://localhost:3000 e importa `grafana/trading-bot-dashboard.json` (Dashboards → New → Import), com uma ligação PostgreSQL ao host `db:5432`, base de dados `trading`.

Corre num PC local (não em 24/7): quando o PC está desligado o bot pára, e o backfill preenche as velas em falta no arranque seguinte.

## Backtests
Todos os backtests usam dados históricos da Binance e um custo de **0,15% por lado** (0,1% de comissão + 0,05% de slippage), com drawdown calculado vela a vela e comparação com buy & hold.

**Backtest2** testa pares de médias (5/20, 10/50, 20/50, 50/200) em velas de 1d, 4h, 1h e 1m, divididos em duas metades. Em 1m (5/20), a estratégia perde com as comissões: 243 operações em 5 dias, -54% contra -6% do buy & hold. Foi o que levou a mudar o bot de 1m para 4h.

**Backtest3** testa 162 variações (médias 5/30 a 20/100, filtro de tendência SMA200, stop-loss 2% e 5%, take-profit 5% e 10%) em 5 anos de velas de 4h e diárias, com **separação treino/teste** (60% / 40%): a combinação é escolhida no treino e avaliada em dados que nunca viu.

| | 4h | Diário |
|---|---|---|
| Buy & hold (treino / teste) | +128,7% / -32,9% | +380,0% / -30,5% |
| Estratégia atual do bot (20/50, stop 2%) no teste | +1,8% | -0,5% |
| Melhor do treino (treino → teste) | +238,9% → -12,9% | +293,3% → +31,9% (10 operações) |
| Combinações com lucro no teste | 33% | 64% |
| Mediana do teste | -11,9% | +4,5% |

### Conclusão
- **Não há vantagem robusta.** A melhor combinação do treino perde ou deixa de ganhar nos dados novos (overfitting), e a mediana das 162 combinações em 4h é negativa no teste.
- O seguimento de tendência funciona sobretudo como **redução de risco**: perde bem menos do que o buy & hold quando o mercado cai (4h: +1,8% contra -32,9%), mas fica atrás quando o mercado sobe (diário no treino: o buy & hold deu +380%, e a melhor estratégia +293%).
- O filtro de tendência (SMA200) não melhorou os resultados.
- Os dados cobrem apenas **um ciclo de mercado** (sobretudo subida no treino, sobretudo descida no teste), e os resultados diários assentam em poucas operações. Qualquer conclusão é provisória.
- Por isso o bot mantém a estratégia simples (20/50 em 4h) e corre na testnet, para comparar o desempenho em tempo real com o backtest.

### Limitações
Entradas e saídas ao fecho da vela, stop-loss e take-profit avaliados ao fecho no backtest (o bot real verifica ao minuto), sem profundidade de mercado nem impacto no preço.

## Aviso
Projeto educativo. Não é aconselhamento financeiro.