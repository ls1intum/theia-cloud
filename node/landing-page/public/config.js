window.theiaCloudConfig = {
  appId: 'asdfghjkl',
  appName: 'Theia Blueprint',
  // Set to false for demo/development mode without Keycloak server
  // Set to true when you have a real Keycloak instance running
  useKeycloak: false,
  serviceUrl: 'http://localhost:8081/service',
  appDefinition: 'ghcr.io/ls1intum/theia/java-17:latest',
  useEphemeralStorage: true,
  additionalApps: [
    {
      appId: "c-latest",
      appName: "C"
    },
    {
      appId: "java-17-latest", 
      appName: "Java"
    },
    {
      appId: "javascript-latest",
      appName: "Javascript"
    },
    {
      appId: "ocaml-latest",
      appName: "Ocaml"
    },
    {
      appId: "python-latest",
      appName: "Python"
    },
    {
      appId: "rust-latest",
      appName: "Rust"
    }
  ],
  disableInfo: false,
  infoText: 'We are offering a online programming environment perfectly tailored for your project, please log in to continue.',
  infoTitle: 'Welcome to Theia - the Online IDE for Artemis',
  loadingText: 'Preparing your personal Online IDE...',
  logoFileExtension: 'png',
  // Keycloak configuration - only used when useKeycloak: true
  // For development, you can use a local Keycloak or minikube setup
  // Example: "https://192.168.59.101.nip.io/keycloak" for minikube
  keycloakAuthUrl: "http://localhost:8080/auth/",
  keycloakRealm: "TheiaCloud",
  keycloakClientId: "theia-cloud",
};
