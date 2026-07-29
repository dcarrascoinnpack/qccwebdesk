# Quality Control Center Web

Migración web paralela del sistema Quality Control Center de TI Faret.

## Arquitectura

- Backend: Java 17 + Spring Boot
- Frontend: React + TypeScript + Vite
- Base de datos: MySQL
- Producción: IIS como reverse proxy
- Aplicación actual: Photino .NET 8, operativa en paralelo

## Estructura

- backend/: API Spring Boot
- frontend/: aplicación React
- database/: scripts y documentación de base de datos
- docs/: documentación técnica y funcional
- deploy/: configuración de despliegue
