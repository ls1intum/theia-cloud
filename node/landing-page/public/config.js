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
      appName: "C",
      logo: '/assets/logos/c-logo.png'
    },
    {
      appId: "java-17-latest", 
      appName: "Java",
      logo: '/assets/logos/java-logo.png'
    },
    {
      appId: "javascript-latest",
      appName: "Javascript",
      logo: '/assets/logos/javascript-logo.png'
    },
    {
      appId: "ocaml-latest",
      appName: "Ocaml",
      logo: '/assets/logos/ocaml-logo.png'
    },
    {
      appId: "python-latest",
      appName: "Python",
      logo: '/assets/logos/python-logo.png'
    },
    {
      appId: "rust-latest",
      appName: "Rust",
      logo: '/assets/logos/rust-logo.png'
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
