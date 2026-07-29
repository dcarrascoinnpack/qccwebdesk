# Arquitectura de Quality Control Center Web

## Objetivo

Migrar progresivamente Quality Control Center desde Photino .NET 8 hacia una aplicación web Java y React, manteniendo ambas versiones operativas en paralelo.

## Principios

- Photino continúa funcionando en producción.
- La aplicación web utilizará la misma base de datos MySQL.
- No se modificarán tablas automáticamente mediante Hibernate.
- Las reglas funcionales existentes en Photino son la referencia.
- La migración será gradual, módulo por módulo.
- Backend y frontend estarán desacoplados.
- IIS actuará como reverse proxy en producción.

## Tecnología

### Backend

- Java 17
- Spring Boot
- Maven
- Spring Web
- Spring Data JPA
- Spring Security
- Bean Validation
- Actuator
- Flyway controlado

### Frontend

- React
- TypeScript
- Vite

### Producción

- IIS
- API publicada mediante URL HTTPS
- Spring Boot ejecutándose como servicio de Windows
- MySQL existente

## Organización del backend

Se utilizará arquitectura modular por funcionalidad.

Cada módulo podrá contener:

- controller
- dto
- entity
- repository
- service
- mapper

Los componentes compartidos estarán bajo `shared`.

## Módulos iniciales identificados

1. Autenticación y autorización
2. Usuarios
3. Empresas
4. Dashboard
5. Registros de control
6. Registros de producción
7. Laboratorio
8. Máquinas
9. No conformidades
10. Control documental

## Reglas de acceso a datos

- `ddl-auto: none`
- Flyway deshabilitado inicialmente
- No crear ni alterar tablas automáticamente
- No exponer entidades JPA directamente por HTTP
- Controller llama a Service
- Service llama a Repository
- Las respuestas públicas utilizan DTO
