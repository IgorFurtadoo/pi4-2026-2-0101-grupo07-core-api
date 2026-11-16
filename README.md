# Lemma

Aplicação web de carteira de criptoativos custodial para a rede Ethereum Sepolia Testnet, que permi>

## Integrantes

- Igor de Toledo Furtado
- Lucas Athanasio Bueno de Andrade
- Guillermo Perdomo Sevilla Neto
- Nicollas Fabbio

## Documentação de fluxos e contratos

Os fluxos do sistema (visão geral e E1–E9) estão no repositório do frontend: https://github.com/Igo>

Os contratos deste módulo estão em docs/contratos/.

## Como abastecer a carteira de um usuário (Sepolia)

A carteira de cada usuário é criada vazia pelo Blockchain Service no cadastro. Para ter saldo, ela recebe ETH da **carteira de demonstração**: uma carteira custodial do Blockchain Service abastecida antes na torneira PoW da Sepolia. O `scripts/e2e-demo.sh` faz isso no passo 3, com um pedido de envio (CT10) direto ao Blockchain Service, origem na carteira de demonstração e destino no endereço do usuário.

- Informe o endereço público da carteira de demonstração em `DEMO_WALLET_ADDRESS`. O roteiro para, sem enviar nada, se ela tiver menos de 0.01 ETH.
- Valor: `FUND_ETH`, padrão 0.003, no máximo 0.005.
- Para enviar, a carteira do usuário precisa de **valor + 500.000 × preço do gás**, porque o Blockchain Service usa limite de gás fixo de 500.000. Com menos, a rede recusa e a transação termina `FAILED`/`REJECTED_BY_NETWORK`.
- Só testnet: nunca mainnet nem valores reais.
