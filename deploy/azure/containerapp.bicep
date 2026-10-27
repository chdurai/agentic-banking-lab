// Authoring baseline, not deployed acceptance. Dependencies must already exist in this resource group.
// Keep one replica and provider calls disabled until independent deployment/runtime gates pass.

@description('Azure Container Registry name')
param acrName string

@description('Immutable ACR image digest, including the sha256: prefix')
@minLength(71)
@maxLength(71)
param imageDigest string

@description('Existing application-dedicated RBAC-enabled Key Vault containing provider, database and caller secrets')
param keyVaultName string

@description('Existing PostgreSQL flexible server with private connectivity and DNS to the environment subnet')
param postgresServerName string

@description('Existing PostgreSQL database')
param postgresDatabaseName string

@description('Database login with access to the application database, not a server administrator')
param postgresUser string

@description('Existing dedicated Container Apps infrastructure subnet resource ID')
param infrastructureSubnetId string

@description('Versionless Key Vault secret name for the NVIDIA API key')
param providerSecretName string = 'nim-api-key'

@description('Versionless Key Vault secret name for the database password')
param databaseSecretName string = 'platform-db-password'

@description('Versionless Key Vault secret name for the bootstrap bearer credential (at least 24 characters)')
param callerSecretName string = 'platform-operator-token'

@description('Tenant for the bootstrap platform operator; this does not grant cross-tenant data access')
@minLength(1)
param operatorTenant string

@description('Auditable actor identifier for the bootstrap platform operator')
@minLength(1)
param operatorActor string

@description('Distinct, entitled fallback model on the approved NIM endpoint; startup rejects equal primary/fallback routes')
@minLength(1)
param fallbackModel string

@description('Region recorded in the reviewed provider agreement, not an inferred deployment guarantee')
@minLength(1)
param reviewedProviderRegion string

@description('Reviewed provider agreement / approval reference')
@minLength(1)
param providerTermsReference string

@description('Explicit reviewed no-training attestation; never defaulted to true')
@allowed([true])
param noTrainingReviewed bool

@description('Explicit reviewed retention attestation; never defaulted to true')
@allowed([true])
param retentionReviewed bool

@description('Conservative estimated token/minute admission cap; calibrate against the actual provider contract')
@minValue(1)
param tokensPerMinute int = 60000

@description('Location')
param location string = resourceGroup().location

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: acrName
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
  name: keyVaultName
}

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' existing = {
  name: postgresServerName
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'agentic-banking-runtime'
  location: location
}

resource registryPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, identity.id, 'AcrPull')
  scope: registry
  properties: {
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')
  }
}

resource secretRead 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, identity.id, 'KeyVaultSecretsUser')
  scope: vault
  properties: {
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
  }
}

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'agentic-banking-logs'
  location: location
  properties: { sku: { name: 'PerGB2018' } }
}

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'agentic-banking-env'
  location: location
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: infrastructureSubnetId
      internal: true
    }
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalytics.properties.customerId
        sharedKey: logAnalytics.listKeys().primarySharedKey
      }
    }
  }
}

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'agentic-banking'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: {
        external: true
        targetPort: 8080
        allowInsecure: false
        transport: 'http'
      }
      registries: [
        {
          server: registry.properties.loginServer
          identity: identity.id
        }
      ]
      secrets: [
        {
          name: 'nim-api-key'
          keyVaultUrl: '${vault.properties.vaultUri}secrets/${providerSecretName}'
          identity: identity.id
        }
        {
          name: 'db-password'
          keyVaultUrl: '${vault.properties.vaultUri}secrets/${databaseSecretName}'
          identity: identity.id
        }
        {
          name: 'operator-token'
          keyVaultUrl: '${vault.properties.vaultUri}secrets/${callerSecretName}'
          identity: identity.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'agentic-banking'
          image: '${registry.properties.loginServer}/agentic-banking@${imageDigest}'
          resources: { cpu: json('1.0'), memory: '2Gi' }
          env: [
            { name: 'NVIDIA_API_KEY', secretRef: 'nim-api-key' }
            { name: 'SPRING_PROFILES_ACTIVE', value: 'production' }
            { name: 'PLATFORM_PERSISTENCE', value: 'jdbc' }
            { name: 'PLATFORM_DB_URL', value: 'jdbc:postgresql://${postgres.properties.fullyQualifiedDomainName}:5432/${postgresDatabaseName}?sslmode=verify-full' }
            { name: 'PLATFORM_DB_USER', value: postgresUser }
            { name: 'PLATFORM_DB_PASSWORD', secretRef: 'db-password' }
            { name: 'PLATFORM_CALLERS_REQUIRED', value: 'true' }
            { name: 'PLATFORM_CALLERS_CREDENTIALS_OPERATOR_SECRET', secretRef: 'operator-token' }
            { name: 'PLATFORM_CALLERS_CREDENTIALS_OPERATOR_TENANTID', value: operatorTenant }
            { name: 'PLATFORM_CALLERS_CREDENTIALS_OPERATOR_ACTOR', value: operatorActor }
            { name: 'PLATFORM_CALLERS_CREDENTIALS_OPERATOR_ROLES', value: 'PLATFORM_ADMIN' }
            { name: 'PLATFORM_IDEMPOTENCY_REQUIRED', value: 'true' }
            { name: 'PLATFORM_LLM_PROVIDER', value: 'nvidia-nim' }
            { name: 'PLATFORM_LLM_PROVIDERS_APPROVED', value: 'nvidia-nim' }
            { name: 'PLATFORM_LLM_GOVERNANCE_APPROVEDENDPOINTS', value: 'https://integrate.api.nvidia.com' }
            { name: 'LLM_REVIEWED_REGION', value: reviewedProviderRegion }
            { name: 'LLM_NO_TRAINING_REVIEWED', value: string(noTrainingReviewed) }
            { name: 'LLM_RETENTION_REVIEWED', value: string(retentionReviewed) }
            { name: 'LLM_TERMS_REFERENCE', value: providerTermsReference }
            { name: 'PLATFORM_LLM_ALLOWFALLBACKEQUALSPRIMARY', value: 'false' }
            { name: 'AGENTS_FALLBACKMODEL', value: fallbackModel }
            { name: 'PLATFORM_LLM_RESILIENCE_KILLSWITCH', value: 'true' }
            { name: 'PLATFORM_LLM_RESILIENCE_QUOTA_TPM', value: string(tokensPerMinute) }
            { name: 'ANOMALY_WATCHER_ENABLED', value: 'false' }
            { name: 'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED', value: 'true' }
            { name: 'MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE', value: 'readinessState,db' }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              periodSeconds: 10
              timeoutSeconds: 3
              failureThreshold: 30
            }
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              periodSeconds: 30
            }
            {
              type: 'Readiness'
              httpGet: { path: '/actuator/health/readiness', port: 8080 }
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 1
        rules: [
          {
            name: 'http-scale'
            http: { metadata: { concurrentRequests: '50' } }
          }
        ]
      }
    }
  }
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identity.id}': {}
    }
  }
  dependsOn: [
    registryPull
    secretRead
  ]
}

output fqdn string = app.properties.configuration.ingress.fqdn
output runtimeIdentityPrincipalId string = identity.properties.principalId
