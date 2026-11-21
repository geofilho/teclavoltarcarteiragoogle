# Carteira Rápida G

App para Wear OS (feito para o Galaxy Watch Ultra 2, funciona do Galaxy Watch 4 em diante) que faz o gesto **segurar o botão Voltar** abrir a **Carteira do Google** em vez da Samsung Wallet.

## Como funciona

A Samsung não deixa nenhum app mudar a função de segurar o botão Voltar. Por isso o app usa um serviço de acessibilidade:

1. Você segura o botão Voltar e a Samsung Wallet começa a abrir.
2. O serviço percebe isso e abre a Carteira do Google por cima, na hora.

Pode aparecer um piscar rápido da Samsung Wallet antes da troca.

- Se o relógio avisar o serviço sobre o botão Voltar, só o atalho do botão é trocado: abrir a Samsung Wallet pela lista de apps continua normal.
- Se o relógio não avisar (a Samsung pode reservar o botão), a Carteira do Google abre sempre que a Samsung Wallet abrir.
- Se a Carteira do Google não estiver instalada, o app não interfere e avisa na tela.

O app não lê a tela, não usa internet, não bloqueia nenhum botão e não guarda dados.

## Baixar

A cada alteração na branch `main`, o GitHub gera o APK sozinho. Ele fica na página **Releases**, na versão "ultima-versao", com o nome `CarteiraRapidaG.apk`.

## Instalar no relógio

O relógio não tem Play Store para APKs de fora, então a instalação é por depuração sem fio:

1. No relógio: Configurações, Sobre o relógio, Informações do software. Toque 5 vezes em "Versão do software" para liberar as Opções do desenvolvedor.
2. Em Opções do desenvolvedor, ligue "Depuração ADB" e "Depuração sem fio". Anote o endereço IP e a porta.
3. Instale com um app de celular, como o Bugjaeger ou o Wear Installer 2, ou pelo computador:

```
adb pair IP:PORTA_DE_PAREAMENTO
adb connect IP:PORTA
adb install -r CarteiraRapidaG.apk
```

Como o APK é sempre assinado com a mesma chave (`keystore/debug.keystore`), as novas versões instalam por cima da anterior.

## Ativar

Abra o Carteira Rápida G no relógio, toque em **Ativar permissão** e ligue o Carteira Rápida G nos ajustes de acessibilidade.

Se a chave estiver cinza (configuração restrita), há dois caminhos:

- No relógio: Configurações, Aplicativos, Carteira Rápida G, "Permitir configurações restritas".
- Pelo computador:

```
adb shell appops set com.geofilho.carteirarapidag ACCESS_RESTRICTED_SETTINGS allow
```

Se mesmo assim a chave não ligar, dá para ativar direto pelo ADB:

```
adb shell settings put secure enabled_accessibility_services com.geofilho.carteirarapidag/.BotaoVoltarService
adb shell settings put secure accessibility_enabled 1
```

O primeiro desses dois comandos substitui outros serviços de acessibilidade que estejam ligados. Se você usa o TalkBack ou outro serviço, veja antes o que está ligado com `adb shell settings get secure enabled_accessibility_services` e junte tudo separado por `:`.

## Acessibilidade

- Todos os controles são botões com texto falado pelo TalkBack. A ordem de leitura vai de cima para baixo. O aviso de status é lido sozinho quando muda.
- O app usa o tema do sistema, então respeita o tema escuro, o alto contraste, a inversão de cores, o negrito e o tamanho de fonte. Os textos ficam entre 15 e 18 sp, sem altura fixa e sem tudo em maiúsculas.
- Os botões têm área de toque de 52 dp de altura.
- Dá para rolar a tela girando a moldura do relógio.

## Desenvolvimento

- Kotlin, sem bibliotecas externas. Usa minSdk 30 (Wear OS 3) e targetSdk 35.
- Compilar: `./gradlew assembleDebug`
- Os pacotes da Samsung Wallet reconhecidos estão em `Carteiras.kt`. Se a Samsung mudar o nome, é só adicionar o novo lá.
