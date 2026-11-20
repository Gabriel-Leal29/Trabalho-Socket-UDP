# Mini Servidor de Arquivos (UDP)

Servidor de arquivos com clientes gráficos (Swing), comunicando **só por UDP**, com
upload, download, lista sincronizada em tempo real e transferência confiável
(fragmentação, ACK, janela deslizante, timeout, retransmissão e limite de tentativas).

## Como rodar

**No IntelliJ:** abra a pasta `MiniServidorArquivos` e use as configurações de
execução **Servidor** e **Cliente** (canto superior direito).
Para abrir dois clientes na mesma máquina, em *Edit Configurations → Cliente →
Modify options*, marque **Allow multiple instances**.

**Pelo .jar** (Java 17 ou mais novo):

```bash
./build.sh
java -jar dist/MiniServidorArquivos.jar servidor
java -jar dist/MiniServidorArquivos.jar cliente
```

Com duplo clique no `.jar`, ele pergunta se é servidor ou cliente.

## Apresentação em duas máquinas

1. Ligue as duas máquinas na mesma rede (mesmo Wi-Fi ou cabo).
2. Abra o **servidor** em uma delas. A janela mostra o IP a usar, por exemplo
   `Servidor ATIVO — nos clientes use IP 192.168.0.10 e porta 5000`.
3. Na outra, abra o **cliente**, digite esse IP e clique em **Conectar**.
4. **Firewall:** na primeira execução, o Windows pergunta se libera o Java. Marque
   **Redes privadas** (e públicas, se a rede estiver como pública). No macOS, se o
   firewall estiver ligado, clique em **Permitir**.
5. Para mostrar retransmissões, duplicados e fragmentos fora de ordem, use o campo
   **Perda simulada (%)** do cliente e/ou do servidor (10% a 20% funciona bem).

Arquivos gerados pelo servidor (na pasta do projeto, ou em `~/MiniServidorArquivos`):

- `arquivos_servidor/`: a pasta central.
- `log_servidor.txt`: o log no formato `[AAAA-MM-DD HH:MM:SS] [IP:porta] [OPERAÇÃO] [STATUS] detalhes`.

Os downloads do cliente vão para `~/Downloads/MiniServidorArquivos/`.

## Estrutura

```
src/miniservidor/
├── Iniciar.java              lançador do .jar (servidor ou cliente)
├── comum/                    usado pelos dois lados
│   ├── Protocolo.java        códigos dos comandos, tamanhos, timeouts, códigos de erro
│   ├── Mensagem.java         formato dos datagramas (montar e ler bytes)
│   ├── CanalUdp.java         DatagramSocket + perda simulada
│   ├── EmissorJanela.java    envio confiável (janela deslizante, repetição seletiva)
│   ├── ReceptorArquivo.java  recepção e remontagem dos fragmentos + SHA-256
│   └── ...
├── servidor/
│   ├── ServidorUdp.java      thread despachante, clientes ativos, UPDATE_LIST
│   ├── SessaoUpload.java     uma thread por upload
│   ├── SessaoDownload.java   uma thread por download
│   ├── RepositorioArquivos.java  pasta central e nomes duplicados
│   ├── LogServidor.java      log_servidor.txt
│   └── JanelaServidor.java   janela do servidor
└── cliente/
    ├── ConexaoCliente.java   REGISTER, PING, pedidos com retransmissão, thread receptora
    ├── TarefaUpload.java     / TarefaDownload.java
    └── JanelaCliente.java    interface gráfica
```

## Protocolo

Todo datagrama começa com `tipo (1 byte) + id (4 bytes)`. Números são big-endian e
textos são UTF (2 bytes de tamanho + UTF-8). A tabela completa está em
`comum/Mensagem.java`.

| Comando (código) | Sentido | Campos depois do cabeçalho | Resposta |
|---|---|---|---|
| REGISTER (1) | C→S | idCliente | ACK |
| ACK (2) | ambos | tipoConfirmado, fragmento, info | — |
| LIST (3) | C→S | (vazio) | UPDATE_LIST |
| UPLOAD (4) | C→S | nome, tamanho, nº de fragmentos, SHA-256 | ACK (nome final) / ERROR |
| DATA (5) | ambos | nº do fragmento, payload (até 1400 B) | ACK do fragmento |
| DOWNLOAD (6) | C→S | nome | METADATA + DATA / ERROR 404 |
| UPDATE_LIST (7) | S→C | versão, lista (nome, tamanho, data) | ACK |
| ERROR (8) | ambos | código, mensagem | — |
| METADATA (9) | S→C | nome, tamanho, nº de fragmentos, SHA-256 | ACK |
| PING (10) | C→S | (vazio) | ACK / ERROR 401 |
| BYE (11) | C→S | (vazio) | ACK |

## Decisões de projeto

| Decisão | Escolha | Por quê |
|---|---|---|
| Concorrência no servidor | 1 thread despachante + 1 thread por transferência | O UDP usa um único socket; a despachante entrega cada datagrama na fila da transferência certa. |
| Nome duplicado | **Renomear** (`foto (1).jpg`) | Ninguém perde arquivo, e dois uploads simultâneos com o mesmo nome não se misturam. |
| Tamanho do fragmento | 1400 bytes | 1400 + 9 de cabeçalho < 1472 (MTU 1500 − IP − UDP): sem fragmentação IP. |
| Confirmação | ACK por fragmento, janela de 64 (repetição seletiva) | Bem mais rápido que pare-e-espere; só o fragmento perdido é reenviado. |
| Timeout | 250 ms, dobrando até 1 s; retransmissão rápida após 3 ACKs posteriores | Rede local tem RTT de milissegundos; a retransmissão rápida evita esperar o timeout. |
| Limite de tentativas | 10 timeouts seguidos no mesmo fragmento (~9 s) | Distingue perda ocasional de "o outro lado caiu". |
| Integridade | SHA-256 do arquivo inteiro | Detecta arquivo corrompido; o arquivo só entra na pasta central se o hash bater. |
| Duplicados / fora de ordem | Cada fragmento é gravado na sua posição; um BitSet marca o que já chegou | A ordem de chegada não importa; um duplicado só tem o ACK reenviado. |
| Perda de conexão | PING a cada 1 s; 6 s sem resposta = "conexão perdida" | UDP não tem conexão; o heartbeat simula uma. |
| Último ACK perdido | O receptor lembra das transferências concluídas por 60 s | Como o TIME_WAIT do TCP: reconfirma fragmentos repetidos em vez de dar erro. |
