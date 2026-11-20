# NyRoCore

`scripts/build_and_install_nyrocore.sh` compila, assina e instala o APK na cópia NyRoOS do `dev_phone`. A opção `--build-only` gera o APK local sem reiniciar o AVD.

Em **Atualização**, o NyRoCore consulta o catálogo GitHub Raw configurado em `custom/ota_repo.conf` no build. Para escolher outra fonte, selecione `NyRoOS/ota_config.json` pelo seletor do Android. O arquivo contém `manifestUrl`. Cada verificação relê o documento vinculado.

O catálogo `ota.json` contém `manifest` e `signature` em Base64 e `manifestSha256` em hexadecimal. O corpo assinado é um JSON UTF-8 que inclui a versão, o changelog e os hashes dos assets. Quando `otaAvailable` é verdadeiro, o corpo inclui `device`, `baseFingerprint`, `url`, `size` em bytes, `sha256` e `demo`. A assinatura é RSA com SHA-256 e a chave pública de `ota/catalog-public.der` é incluída no APK durante o build. O pacote de release também precisa ter uma assinatura reconhecida pelo recovery e metadados OTA compatíveis com a build instalada. A instalação exige confirmação e acesso à área de staging do recovery.

Em **Orquestrador**, vincule o relatório `trusted_apps.json` pelo seletor. A aprovação local associa o nome do pacote ao certificado instalado. Para alterar permissões ou otimização de bateria, abra o respectivo controle do Android pelo cartão do app.

Execute estes comandos na raiz do projeto para repetir a validação no emulador.

```bash
source env.sh
python3 -I -B core/tools/verify_dev_phone.py
```

O teste usa um servidor HTTP de loopback e uma versão de demonstração 1.1. O botão de instalação fica desativado para esse artefato.

Use `python3 -I -B core/tools/verify_dev_phone.py --catalog ota/ota.json` para testar o catálogo inicial gerado pelo empacotador no emulador. Esse modo usa o catálogo do projeto e verifica a rejeição de um SHA-256 adulterado.
