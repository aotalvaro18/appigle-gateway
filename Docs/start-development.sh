#!/bin/bash
echo "Iniciando sesión en Azure..."
az login

echo "Verificando servicios..."
az containerapp list --resource-group appigle-dev-rg --output table

echo "Asegurando que los servicios estén activos..."
az containerapp update --name appigle-gateway --resource-group appigle-dev-rg --min-replicas 0 --max-replicas 2
az containerapp update --name auth-service --resource-group appigle-dev-rg --min-replicas 0 --max-replicas 2

echo "Iniciando base de datos si está pausada..."
az postgres flexible-server start --name appigle-postgres --resource-group appigle-dev-rg

echo "Obteniendo URL del Gateway..."
GATEWAY_URL=$(az containerapp show --name appigle-gateway --resource-group appigle-dev-rg --query properties.configuration.ingress.fqdn -o tsv)
echo "API Gateway disponible en: https://$GATEWAY_URL"

echo "Entorno listo para desarrollo"