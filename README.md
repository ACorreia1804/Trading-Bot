# Trading Bot (Java) · 5 mercados

Bot de trading em Java que negoceia **apenas 5 mercados** (Bitcoin, S&P 500, NASDAQ-100, Ouro e Petróleo) na conta **paper trading da Alpaca** (dinheiro de simulação). Velas e trades ficam em PostgreSQL e há um dashboard em Grafana. Projeto de aprendizagem em engenharia de dados: nunca usa dinheiro real.

| Mercado | Símbolo na Alpaca |
|---|---|
| Bitcoin | BTC/USD |
| S&P 500 | SPY (ETF) |
| NASDAQ-100 | QQQ (ETF) |
| Ouro | GLD (ETF) |
| Petróleo | USO (ETF) |

O bot recusa ordens para qualquer outro símbolo e recusa falar com a API de dinheiro real (só aceita a URL de paper trading).

## Como funciona
- Para cada mercado, o **Backtest5** escolheu com base nos dados a família de estratégia (tendência, momentum ou reversão) e o timeframe (1h, 4h ou 1 dia). A configuração final está em `Mercados.java`.
- A cada vela fechada, o `BotMulti` calcula o sinal (`Estrategias.java`) e compra ou vende a mercado (1000 USD por compra). Stop-loss de segurança de 10%.
- A Alpaca é a fonte de verdade para saber se há posição aberta. Se um sinal surge com a bolsa fechada, a ordem fica pendente até a bolsa abrir.
- Velas e trades vão para as tabelas `candles` e `trades`. Se a base de dados falhar, o bot continua a negociar.
- Tudo corre em containers Docker (Docker Compose) com `restart: unless-stopped`.

## Stack
Java 17, Maven, ta4j (backtests antigos), Jackson, API da Alpaca, PostgreSQL 16, Grafana, Docker Compose.

## Como correr
1. Cria uma conta Alpaca, muda para **Paper** e gera as chaves (API Keys).
2. Cria um ficheiro `.env` na raiz (não vai para o git):
   ```
   POSTGRES_PASSWORD=...
   GRAFANA_PASSWORD=...
   ALPACA_KEY=...
   ALPACA_SECRET=...
   ```
3. Arranca tudo:
   ```
   docker compose up -d --build
   docker compose logs -f botmulti
   ```
4. Abre o Grafana em http://localhost:3000 e importa `grafana/trading-bot-dashboard.json` (Dashboards → New → Import), com uma ligação PostgreSQL ao host `db:5432`, base de dados `trading`.
5. Para refazer a escolha das estratégias: `mvn compile exec:java "-Dexec.mainClass=Backtest5"` (precisa de `ALPACA_KEY` e `ALPACA_SECRET` no ambiente).

Corre num PC local (não em 24/7): quando o PC está desligado o bot pára e retoma ao arrancar. As vendas apanham-se sempre (a estratégia olha para o estado atual), mas uma entrada cujo sinal passou com o PC desligado só acontece no sinal seguinte.

## Escolha das estratégias (Backtest5)
Para cada mercado testaram-se 3 famílias × 3 timeframes (1h, 4h, 1d), com os dados divididos no tempo:
- **Treino (50%)**: escolhem-se os melhores parâmetros de cada família e timeframe.
- **Validação (25%)**: escolhe-se a melhor família e timeframe (tem de dar lucro e ter operações).
- **Teste (25%)**: nunca usado nas escolhas.

Custos assumidos por lado: 0,30% em cripto, 0,05% nos ETFs. Execução na abertura da vela seguinte, drawdown em mark-to-market e comparação com buy & hold.

| Mercado | Estratégia | Timeframe | Teste | Buy & hold no teste |
|---|---|---|---|---|
| Bitcoin | Tendência, SMA 20/100 | 4h | -5,3% | -40,6% |
| S&P 500 (SPY) | Reversão, RSI(2)<20 | 1d | +7,7% | +12,8% |
| NASDAQ-100 (QQQ) | Momentum, 20 velas | 1d | +5,5% | +10,2% |
| Ouro (GLD) | Momentum, 200 velas | 1h | +9,9% | -4,0% |
| Petróleo (USO) | Reversão, RSI(2)<10 | 1h | +7,0% | +103,6% |

### Conclusões e cuidados
- Os dados contrariaram parte das hipóteses iniciais: o Bitcoin deu melhor com tendência do que com momentum, o NASDAQ e o ouro deram melhor com momentum. S&P 500 e petróleo confirmaram a reversão.
- Em SPY, QQQ e USO a estratégia ganha **menos** que comprar e esperar (o petróleo subiu mais de 100% no teste). A vantagem é perder menos quando o mercado cai, não ganhar mais.
- No Bitcoin, o período de teste foi uma queda de ~40% e nenhuma das 9 combinações deu lucro. É o mercado mais fraco.
- No SPY, o script escolheu momentum 4h (-1,1% no teste, poucas operações na validação). Troquei à mão por reversão 1d, positiva em treino, validação e teste. Como o teste influenciou esta decisão, deixou de ser 100% limpo.
- Cada mercado teve 9 candidatos, por isso entra alguma sorte, e o teste é uma única amostra. Os dados cobrem poucos ciclos de mercado.
- Com 60 a 120 operações em 1h (ouro, petróleo), o resultado depende muito dos custos assumidos.

## Fase 1: bot da Binance (histórico do projeto)
A primeira versão negociava BTCUSDT na testnet da Binance (SMA 20/50 em velas de 4h). O **Backtest2** (pares de médias em 1d, 4h, 1h e 1m) mostrou que em 1m as comissões destroem a estratégia (243 operações em 5 dias, -54% contra -6% do buy & hold), o que levou a mudar para 4h. O **Backtest3** (162 variações com separação treino/teste) mostrou que a melhor combinação do treino perde nos dados novos (overfitting) e que a tendência serve sobretudo para reduzir risco. Essa lição motivou o desenho treino/validação/teste do Backtest5.

## Limitações
Entradas e saídas ao fecho da vela, sem profundidade de mercado nem impacto no preço, dados IEX (gratuitos) para os ETFs e custos estimados. Os resultados da simulação não garantem nada em mercado real.

## Aviso
Projeto educativo. Não é aconselhamento financeiro.