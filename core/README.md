# NyRoCore

`scripts/build_and_install_nyrocore.sh` compila, assina e instala o APK na cópia NyRoOS do `dev_phone`. A opção `--build-only` gera o APK local sem reiniciar o AVD.

Em **Atualização**, o NyRoCore consulta o catálogo GitHub Raw configurado em `custom/ota_repo.conf` no build. Para escolher outra fonte, selecione `NyRoOS/ota_config.json` pelo seletor do Android. O arquivo contém `manifestUrl`. Cada verificação relê o documento vinculado.

No Galaxy A36 com One UI, instale o NyRoCore 1.2 uma vez pelo APK assinado. Em **Atualização → Verificar atualizações**, o catálogo HTTPS lista versões superiores dos apps NyRoOS já instalados. Após baixar, escolha **Instalar** e confirme no Android. Na primeira vez, o Android pode solicitar permissão para instalar APKs pelo NyRoCore.

Para atualizar os APKs pelo repositório público, prepare a versão com `scripts/publish_ota_release.sh --version X.Y.Z --notes CAMINHO --build-apks --raw-assets`. Publique os dois APKs em `ota/releases/vX.Y.Z/` e o catálogo assinado em `ota/ota.json` na branch `main` na mesma atualização. Cada entrada de APK declara pacote, código e nome de versão, tamanho e SHA-256. O NyRoCore também verifica o certificado do app instalado antes de abrir o instalador.

O catálogo `ota.json` contém `manifest` e `signature` em Base64 e `manifestSha256` em hexadecimal. O corpo assinado é um JSON UTF-8 que inclui a versão, o changelog e os hashes dos assets. Quando `otaAvailable` é verdadeiro, o corpo inclui `device`, `baseFingerprint`, `url`, `size` em bytes, `sha256` e `demo`. A assinatura é RSA com SHA-256 e a chave pública de `ota/catalog-public.der` é incluída no APK durante o build. O pacote de release também precisa ter uma assinatura reconhecida pelo recovery e metadados OTA compatíveis com a build instalada. A instalação exige confirmação e acesso à área de staging do recovery.

Em **Orquestrador**, vincule o relatório `trusted_apps.json` pelo seletor. O acompanhamento local associa o nome do pacote ao certificado instalado. Os cartões dos apps dão acesso às telas de permissões e bateria do Android.

Com o Shizuku iniciado por ADB, toque em **Conectar ao Shizuku** e autorize o NyRoCore. **Ações via Shizuku** oferece permissões declaradas, acessos especiais suportados, otimização de bateria e desativação conforme o pacote selecionado. Cada alteração pede confirmação. O serviço executa operações predefinidas como UID 2000 e é acessível pelo próprio NyRoCore. As ações ficam disponíveis enquanto o Shizuku estiver ativo. [API oficial do Shizuku](https://github.com/RikkaApps/Shizuku-API)

No Galaxy A36, `scripts/deploy_nyroos_a36.sh` instala os APKs no usuário principal, configura o HOME e salva o estado dos apps opcionais. `scripts/restore_a36_stock_apps.sh` restaura os estados registrados. `--ui` também volta ao HOME anterior, às escalas de animação e ao modo de tela anteriores. O papel de parede pode ser trocado em **Configurações → Papel de parede e estilo**.

Execute estes comandos na raiz do projeto para repetir a validação no emulador.

```bash
source env.sh
python3 -I -B core/tools/verify_dev_phone.py
```

O teste usa um servidor HTTP de loopback e uma versão de demonstração 1.1. O botão de instalação fica desativado para esse artefato.

Use `python3 -I -B core/tools/verify_dev_phone.py --catalog ota/ota.json` para testar o catálogo inicial gerado pelo empacotador no emulador. Esse modo usa o catálogo do projeto e verifica a rejeição de um SHA-256 adulterado.
