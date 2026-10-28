# ADR 0014: Imagens de questões públicas por URL, Swagger desligado e rotas legadas

## Status
Accepted (28/10/2026). Revisitar se uma imagem passar a poder conter dados pessoais.

---

## Context

A análise de arquitetura (issue #455) levantou três pontos de superfície exposta:

1. `/uploads/questions/*/*` e `/uploads/images/*/*` respondem sem autenticação.
2. O Swagger/OpenAPI estava ligado por omissão em instalações que esquecessem as duas variáveis.
3. `/api/simulations` e `/api/exam-rooms` duplicam as rotas de `/api/v1`.

## Decision

### Imagens públicas por URL: aceite, com os limites escritos

As imagens são figuras de enunciados (gráficos, esquemas, partes de textos) e são mostradas dentro de
provas, PDFs e partilhas. O caminho contém um identificador aleatório (UUID v4, 122 bits), por isso não
é adivinhável nem listável: não há índice público e `GET /api/v1/question-images` exige `ADMIN`.

Aceita-se o risco porque o que lá está não identifica ninguém. **Isto deixa de valer** se alguma imagem
puder conter dados pessoais (por exemplo, a folha de um aluno fotografada). Esse caso não pode ir para
`/uploads/questions` nem `/uploads/images`; teria de ter URLs assinadas com validade curta, o que obriga os
proxies do gestor e do aluno a reencaminhar o token e a rever PDF, OCR e partilha. Fica como issue própria
se e quando esse caso existir.

### Swagger: desligado por omissão

`MainApplication` define `springdoc.api-docs.enabled` e `springdoc.swagger-ui.enabled` como `false`
por omissão. Uma variável de ambiente (ou o `.env` de desenvolvimento) liga-os. Era uma decisão que
dependia de um `application.properties` que o repositório ignora.

### Rotas legadas: avisar, medir, retirar depois

Só existem dois prefixos sem versão: `/api/simulations` e `/api/exam-rooms`. Passam a responder com
`Deprecation: true`, `Sunset: Sun, 31 Jan 2027 23:59:59 GMT` e `Link: <…/api/v1/…>; rel="successor-version"`,
e registam uma linha de aviso por método e caminho. O gestor foi migrado para `/api/v1`. A retirada acontece
depois do Sunset, quando o log mostrar que ninguém as chama.

## Consequences

- Uma instalação nova já não publica o mapa da API sem se pedir.
- O risco das imagens fica escrito e com a condição que o faria mudar.
- Retirar as rotas legadas passa a ser uma decisão com dados, não um palpite.
