# SmartAttendance

SmartAttendance es una aplicacion Android para registrar asistencia academica mediante deteccion Bluetooth BLE e integracion con MoodleCloud.

El proyecto permite que un profesor seleccione un curso y una sesion de asistencia de Moodle, inicie una clase activa, detecte alumnos cercanos por Bluetooth, calcule presencia/concentracion y genere reportes sincronizables con Moodle.

## Objetivo

Automatizar el control de asistencia en clase usando senales Bluetooth BLE, manteniendo trazabilidad con las sesiones creadas en MoodleCloud.

El sistema diferencia tres conceptos:

- **Asistencia:** el alumno fue detectado al menos una vez durante la clase activa.
- **Presencia actual:** el alumno esta siendo detectado en vivo por Bluetooth.
- **Concentracion:** porcentaje estimado segun permanencia estable durante la clase.

## Funcionalidades Principales

- Login contra MoodleCloud.
- Diferenciacion de rol profesor/alumno desde los datos de Moodle.
- Listado de cursos del usuario.
- Seleccion de modulo y sesion de asistencia Moodle.
- Creacion de nueva sesion de asistencia desde la app.
- Deteccion Bluetooth BLE en tiempo real.
- Persistencia de clase activa al minimizar o reabrir la app.
- Dashboard en tiempo real para el profesor.
- Reporte final con asistencia, concentracion, cortes de senal y estado Moodle.
- Exportacion/compartir reporte PDF.
- Sincronizacion de asistencia con Moodle Attendance.
- Vista previa de sesiones historicas dentro de la app.

## Arquitectura

El proyecto sigue una estructura cercana a Clean Architecture:

- `data`: acceso remoto, Moodle API, repositorios e infraestructura.
- `domain`: modelos y contratos principales.
- `ui`: pantallas, ViewModels, estados e intenciones de usuario.

La interfaz usa un flujo tipo MVI:

- `Intent`: accion del usuario o evento del sistema.
- `State`: estado observable de la pantalla.
- `Effect`: navegacion, mensajes y acciones de una sola vez.

## Tecnologias

- Kotlin
- Android SDK
- Bluetooth BLE
- Foreground Services
- Retrofit
- Coroutines
- Gson
- Material Components
- Moodle Web Services REST
- Moodle Attendance API

## Flujo del Profesor

1. Inicia sesion con una cuenta de profesor.
2. Selecciona un curso.
3. Selecciona el modulo de asistencia.
4. Elige una sesion existente o crea una nueva.
5. Inicia la clase.
6. La app detecta alumnos por Bluetooth BLE.
7. Durante la clase ve estados, presencia y concentracion.
8. Al finalizar, revisa el reporte.
9. Sincroniza la asistencia con Moodle.
10. Puede exportar o compartir el PDF.

## Flujo del Alumno

1. Inicia sesion con su cuenta Moodle.
2. Activa Bluetooth.
3. Mantiene activa la senal de presencia desde la app.
4. El profesor detecta su dispositivo durante la clase.
5. Su asistencia y concentracion se calculan durante la sesion.

## Consideraciones

- Para probar Bluetooth BLE de forma real se recomienda usar celulares fisicos.
- El emulador sirve para revisar interfaz, login, navegacion y flujo general.
- MoodleCloud debe tener Web Services habilitado y tokens configurados.
- La sincronizacion con Moodle depende de que el token tenga permisos sobre el servicio Attendance.
- No se deben subir credenciales ni tokens reales al repositorio.

## Rama Activa

La rama principal de trabajo para este avance es:

```bash
ramag
```

## Estado Actual

Version funcional para pruebas academicas:

- Login Moodle operativo.
- Cursos y sesiones Moodle integrados.
- Asistencia BLE implementada.
- Persistencia de clase activa mejorada.
- Reportes y PDF disponibles.
- Sincronizacion Moodle implementada.
- UI del profesor y navegacion principal mejoradas.

