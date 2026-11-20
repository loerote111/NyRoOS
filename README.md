# NyRoOS

Projeto Android local com tema NyRoOS, o launcher nativo NyRoLauncher e o app de sistema NyRoCore. A imagem em uso foi validada no AVD `dev_phone` para Android 17 x86_64.

## Workspace

Carregue `env.sh` na raiz do projeto. O ambiente reutiliza o SDK portátil, o JDK, o AVD e as chaves locais em `.tools/`. O código e os assets do NyRoOS ficam no Git. Imagens, pacotes de terceiros, arquivos de trabalho e chaves privadas permanecem locais.

- `launcher/` e `core/` contêm os fontes e recursos Android.
- `custom/` contém a configuração, os assets visuais e os pequenos APKs do projeto. Os APKs de terceiros da camada GApps ficam fora do Git.
- `scripts/` contém a automação do AVD, os builds e o gerador da animação.
- `ota/` contém o catálogo assinado, os certificados públicos de referência e o empacotador de releases.

## GitHub e catálogo

`custom/ota_repo.conf` configura o repositório no formato `USUARIO/REPO`. A URL padrão incorporada no build do NyRoCore é o arquivo `ota/ota.json` da branch `main` via GitHub Raw. O repositório que serve essa URL precisa ser público para o app consultar o catálogo sem credenciais. O documento `NyRoOS/ota_config.json` vinculado no app pode apontar para outra fonte.

### Preparar uma release

Atualize o changelog e incremente o `versionCode` no manifesto de cada app que tiver uma atualização. Use as chaves de assinatura originais dos APKs e a chave privada do catálogo.

```bash
./scripts/publish_ota_release.sh --version 1.0.1 --notes ota/CHANGELOG.md --build-apks
```

Os dois APKs, o catálogo, o changelog e `SHA256SUMS` ficam em `out/releases/v1.0.1/`. Sem `--build-apks`, o script usa os APKs assinados disponíveis em `custom/priv-app/`. Use `--ota /caminho/OTA.zip` para incluir um OTA `BLOCK` compatível já produzido e assinado pelo processo Android de release. O script aceita assets abaixo de 2 GiB para GitHub Releases. O APK do NyRoCore verifica o pacote OTA com as chaves do recovery antes de qualquer instalação.

1. Faça push da tag anotada da release.
2. Publique os assets com o comando `gh release create` exibido pelo script ou faça o upload manual dos mesmos arquivos no GitHub.
3. Envie `ota/ota.json` para `main` depois que os links de download da release estiverem públicos.

O script atualiza o catálogo localmente. Os APKs de atualização rápida são distribuídos como assets para instalação manual. A ação de instalar no NyRoCore é reservada ao pacote OTA compatível.

Em uma cópia autorizada do projeto, `NYROOS_OTA_SIGNING_KEY` pode indicar a chave privada correspondente a `ota/catalog-public.der`. O padrão é a chave já configurada em `.tools/android-user/nyrocore-catalog.pem`. O empacotador verifica as assinaturas dos APKs e os certificados esperados de `ota/apk-signers.json`. A chave privada do catálogo e os keystores dos apps ficam fora do Git.

```bash
python3 -I -B ota/package_release.py --verify-catalog ota/ota.json
cd out/releases/v1.0.0 && sha256sum -c SHA256SUMS
```
