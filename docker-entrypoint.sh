#!/bin/sh
set -e

# Este script prepara el entorno antes de iniciar la aplicación

# Imprimir información de diagnóstico
echo "Starting API Gateway version: $APP_VERSION"
echo "Java version: $(java -version 2>&1)"
echo "Using profile: $SPRING_PROFILES_ACTIVE"

# Verificar variables de entorno críticas
if [ -z "$JWT_SECRET" ] && [ "$SPRING_PROFILES_ACTIVE" = "prod" ]; then
  echo "ERROR: JWT_SECRET must be set in production environment!"
  exit 1
fi

# Configuración específica para Azure - eliminamos referencias a Eureka
if [ "$SPRING_PROFILES_ACTIVE" = "azure" ]; then
  echo "Running in Azure Container Apps environment"
  
  # Personalizar opciones de Java para Azure
  export JAVA_OPTS="$JAVA_OPTS -Dspring.profiles.active=azure"
  
  # Eliminar completamente la sección de verificación de Redis
  if [ "$REDIS_ENABLED" = "true" ] && [ ! -z "$REDIS_HOST" ]; then
    echo "Redis is enabled. Checking connectivity..."
    REDIS_RETRIES=5
    while [ $REDIS_RETRIES -gt 0 ]
    do
      if nc -z "$REDIS_HOST" "$REDIS_PORT"; then
        echo "Redis connection successful."
        break
      fi
      REDIS_RETRIES=$((REDIS_RETRIES-1))
      echo "Waiting for Redis to be available... ($REDIS_RETRIES retries left)"
      sleep 5
    done
    
    if [ $REDIS_RETRIES -eq 0 ]; then
      echo "WARNING: Could not connect to Redis. The application might not function correctly."
      
      # Continuar de todos modos, no bloquear el inicio
      if [ "$REDIS_REQUIRED" = "true" ]; then
        echo "ERROR: Redis connection is required but unavailable. Exiting."
        exit 1
      else
        echo "Redis connection failed, but continuing startup as it's not required."
      fi
    fi
  else
    echo "Redis is disabled or not configured. Skipping connectivity check."
  fi
fi

# Ejecutar el comando proporcionado
echo "Executing command: $@"
exec "$@"