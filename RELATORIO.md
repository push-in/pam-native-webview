# Relatório — enxugamento do núcleo, onda 2

Pacote: `pushinbr/pam-native-webview`. Branch: `main`. Versão pública: `0.1.1`.

## O que foi feito

- A capacidade opcional passou a pertencer ao plugin; SDK mínimo 1.46.0.
- O núcleo conserva aliases PHP depreciados por uma versão, até 1.47.
- Contratos nativos e dados tipados foram preservados; testes acompanham a implementação.
- Release: [0.1.1](https://github.com/push-in/pam-native-webview/releases/tag/v0.1.1). Atualização do Packagist solicitada e aceita pela API.

## Validação

- PHP: 4/4 asserções.
- Compile Kotlin conjunto do núcleo e cinco plugins: aprovado.
- Testes JVM conjuntos: 360/360, zero falhas.
- Compilação dos testes instrumentados conjuntos: aprovada; execução em dispositivo pendente.
- SDK PHP com contratos de compatibilidade dos plugins: aprovado.
- Swift: pendente; Mac proibido.
- QA em dispositivo e medição de CPU/PSS: pendentes; o emulador compartilhado estava ocupado. S10 não utilizado.

## Commits de implementação

```text
f532704 test: name reserved protocol IDs and run PHP contracts independently [skip ci]
4f09575 chore: ignore local build and install artifacts [skip ci]
5529f13 feat: provide the native WebView through a plugin factory [skip ci]
```

O commit deste relatório é posterior aos commits acima. Nenhuma release do núcleo 1.46.0 foi publicada.
