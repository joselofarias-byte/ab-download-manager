# Integración Android experimental

Esta rama incorpora POST /ping y POST /add en 127.0.0.1, puerto predeterminado 15151. Requiere siempre X-Api-Key. En Ajustes de la APK, habilitar Integración con navegador, copiar la clave e introducirla en los ajustes de la extensión junto con el mismo puerto. Abrir la APK una vez para iniciar el servicio. El estado del receptor aparece en Ajustes. La notificación del servicio permanece mientras esté habilitada la integración.

La extensión conserva el capturador original de archivos, lotes y HLS. El overlay elimina Native Messaging, usa IPv4 loopback y amplía el timeout a 2 segundos. Firefox declara gecko_android y un ID propio. Los paquetes son experimentales y sin firma: requieren un navegador que permita instalar extensiones de prueba; Chrome para Android no ofrece esa instalación. La compatibilidad de las APIs de captura y la instalación de cada navegador se deben probar en el dispositivo. Declarar Android en el manifest no garantiza la compatibilidad.

Las peticiones se guardan primero en almacenamiento privado (hasta 32 pendientes, 100 elementos y 128 KiB por petición). Las peticiones normales generan una notificación que abre el diálogo existente. Si no se concedió permiso de notificaciones, quedan accesibles desde Ajustes > Abrir descargas pendientes del navegador. Los modos silentAdd/silentStart usan el motor existente. Los reintentos idénticos dentro de 10 segundos se deduplican; no se promete ejecución exactamente una vez frente a fallos o reinicios durante una importación silenciosa. Las cabeceras Cookie, Referer y User-Agent se conservan; no se registran en logs. Los datos de la bandeja permanecen hasta abrirlos/importarlos.

## Compilación

GitHub Actions construye la APK y los ZIP chrome/firefox del overlay. La base experimental conserva ARM64 y la clave de firma debug de pruebas existente. Para construir la extensión manualmente, clonar su upstream, hacer checkout del commit indicado en prepare.py, ejecutar `python prepare.py <directorio>` y luego `npm ci`, `npm run pack:chrome`, `npm run pack:firefox` desde ese directorio.

## Quetta

El usuario ya tiene Quetta. Como primera prueba puede instalar la extensión oficial AB Download Manager Browser Integration desde Chrome Web Store dentro de Quetta y configurar puerto 15151 y API key de esta APK. La extensión oficial ya dispone de transporte HTTP y puede usarse sin instalar nuestro ZIP si Quetta soporta sus APIs. Su timeout es 500 ms y conserva el intento de Native Messaging. El ZIP Chromium de esta rama es una alternativa experimental con HTTP exclusivamente y timeout de 2 segundos; su instalación depende de que el navegador admita paquetes locales.

## Pruebas físicas

1. Habilitar receptor y comprobar conexión desde la extensión con clave correcta/incorrecta.
2. Descargar un archivo directo; confirmar que llega una sola petición.
3. Descargar un archivo de una página con sesión y comprobar Cookie, User-Agent y Referer.
4. Capturar HLS compatible con el motor existente y seleccionar varios enlaces.
5. Cambiar al navegador externo y esperar más de 10 segundos: el receptor debe seguir disponible.
6. Probar modo normal (notificación), silentAdd y silentStart.
7. Deshabilitar integración: el navegador debe recuperar su descarga normal si la aplicación no responde.
8. Reiniciar el proceso con una petición pendiente y recuperarla desde Ajustes.

La verificación de estas pruebas en HONOR 200 está pendiente. La APK instalada por el usuario antes de esta rama no contiene estos cambios.
