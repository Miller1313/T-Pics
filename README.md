# T-Pics

Respaldo de fotos y videos con etiquetas. App nativa para Android.

- **Galería** agrupada por día, con búsqueda, favoritos, papelera de 30 días y visor con zoom.
- **Etiquetas** con color, varias por foto, y filtro rápido.
- **Subida desde el teléfono**, por carpeta o foto por foto, en segundo plano y sin duplicados.
- **Cuentas separadas**: cada persona ve solo lo suyo. El administrador activa cuentas y registra pagos.
- Los archivos se guardan en Telegram. Los tokens **no** están en la app ni en este repositorio.

## Descargar

Cada cambio compila la app sola. Ve a **Releases** y descarga `T-Pics.apk`.

## Cómo funciona

```
App Android ──(sesión Firebase)──► Worker de Cloudflare ──(token secreto)──► Telegram
     │
     └──► Firebase: cuentas, etiquetas y lista de fotos (sin archivos)
```

| Carpeta | Contenido |
|---|---|
| `app/` | App Android (Kotlin + Jetpack Compose) |
| `worker/worker.js` | Servidor intermediario que guarda los tokens |
| `firebase/database.rules.json` | Reglas de seguridad de la base de datos |

## Puesta en marcha (una sola vez)

### 1. Firebase (proyecto `t-pics-clientes`)

1. **Authentication → Sign-in method →** activa **Correo electrónico/contraseña**.
2. **Realtime Database → Reglas →** pega el contenido de `firebase/database.rules.json` y publica.
   Al publicar, la web antigua deja de funcionar: hazlo cuando estés listo para cambiar a la app.

### 2. Telegram

1. En **@BotFather**, usa `/revoke` para generar tokens nuevos de los bots (los archivos ya subidos siguen funcionando).
2. Ten a mano tu ID de chat (o el de un canal privado donde el bot sea administrador).

### 3. Cloudflare (gratis, sin tarjeta)

1. Crea una cuenta en dash.cloudflare.com → **Workers & Pages → Create → Worker**. Nómbralo `tpics`.
2. **Edit code**, borra lo que haya, pega `worker/worker.js` y pulsa **Deploy**.
3. En **Settings → Variables and Secrets**, agrega como **Secret**:

| Nombre | Valor |
|---|---|
| `BOT_TOKEN` | token del bot general |
| `BOT_TOKEN_PERSONAL` | token del bot personal (solo para leer fotos antiguas) |
| `STORAGE_CHAT_ID` | ID del chat o canal de almacenamiento |
| `SIGNING_SECRET` | un texto largo al azar (40+ caracteres); no lo cambies después |
| `FIREBASE_PROJECT_ID` | `t-pics-clientes` |
| `FIREBASE_DB_URL` | `https://t-pics-clientes-default-rtdb.firebaseio.com` |

4. Copia la dirección del Worker (`https://tpics.TU-CUENTA.workers.dev`). Al abrirla debe decir `"ready": true`.

### 4. En la app

1. Instala el APK y **crea tu cuenta primero**: la primera cuenta registrada queda como administrador.
2. Menú → **Administración** → pega la dirección del Worker → **Guardar y probar**.
3. En la misma pantalla: escribe tus datos de pago e **importa las fotos del T-Pics anterior**.
4. Cuando alguien se registre, actívalo desde **Administración → Usuarios** (`+30 días`).

## Límites conocidos

- Telegram deja descargar 20 MB por archivo: la app parte los archivos grandes en trozos. Máximo 400 MB por video.
- Telegram acepta cerca de un archivo por segundo: las subidas grandes tardan.
- Borrar quita la foto de la app; Telegram solo permite eliminar el archivo original durante las primeras 48 horas.
- La clave de firma del APK está en `signing/`. Mantén el repositorio **privado** si vas a distribuir la app.
