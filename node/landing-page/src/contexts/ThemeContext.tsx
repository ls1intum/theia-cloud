import React, { createContext, useContext, useEffect, useState } from 'react';

type Theme = 'light' | 'dark';

interface ThemeContextType {
  theme: Theme;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
};

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const [theme, setTheme] = useState<Theme>(() => {
    // Check localStorage first, then system preference
    const savedTheme = localStorage.getItem('theme') as Theme;
    if (savedTheme) {
      return savedTheme;
    }
    
    // Check system preference
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return 'dark';
    }
    
    return 'light';
  });

  const toggleTheme = () => {
    setTheme(prevTheme => {
      const newTheme = prevTheme === 'light' ? 'dark' : 'light';
      localStorage.setItem('theme', newTheme);
      return newTheme;
    });
  };

  useEffect(() => {
    // Update CSS variables based on theme
    const root = document.documentElement;
    
    if (theme === 'dark') {
      // Dark theme colors
      root.style.setProperty('--color-text-primary', '#ffffff');
      root.style.setProperty('--color-text-secondary', '#8bb4ff');
      root.style.setProperty('--color-text-muted', 'rgba(255, 255, 255, 0.7)');
      root.style.setProperty('--color-background', '#2a2a40');
      root.style.setProperty('--color-background-card', 'rgba(30, 30, 50, 0.9)');
      root.style.setProperty('--color-border', 'rgba(255, 255, 255, 0.1)');
      root.style.setProperty('--color-border-hover', 'rgba(255, 255, 255, 0.3)');
      root.style.setProperty('--color-button-background', '#383838');
      root.style.setProperty('--color-button-hover', '#484848');
      root.style.setProperty('--color-button-disabled', '#f5f5f5');
      root.style.setProperty('--color-button-text', '#ffffff');
      root.style.setProperty('--color-button-text-disabled', 'rgba(0, 0, 0, 0.2)');
      root.style.setProperty('--color-error', '#ff6b6b');
      root.style.setProperty('--vanta-bg', '#2a2a40');
      root.style.setProperty('--vanta-color1', '#667eea');
      root.style.setProperty('--vanta-color2', '#764ba2');
    } else {
      // Light theme colors
      root.style.setProperty('--color-text-primary', '#1a1a1a');
      root.style.setProperty('--color-text-secondary', '#4a90e2');
      root.style.setProperty('--color-text-muted', 'rgba(26, 26, 26, 0.7)');
      root.style.setProperty('--color-background', '#dbdbdb');
      root.style.setProperty('--color-background-card', '#dbdbdb');
      root.style.setProperty('--color-border', 'rgba(0, 0, 0, 0.1)');
      root.style.setProperty('--color-border-hover', 'rgba(0, 0, 0, 0.3)');
      root.style.setProperty('--color-button-background', '#e9ecef');
      root.style.setProperty('--color-button-hover', '#dee2e6');
      root.style.setProperty('--color-button-disabled', '#f5f5f5');
      root.style.setProperty('--color-button-text', '#1a1a1a');
      root.style.setProperty('--color-button-text-disabled', 'rgba(0, 0, 0, 0.2)');
      root.style.setProperty('--color-error', '#dc3545');
      root.style.setProperty('--vanta-bg', '#f8f9fa');
      root.style.setProperty('--vanta-color1', '#4a90e2');
      root.style.setProperty('--vanta-color2', '#6c5ce7');
    }
  }, [theme]);

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};
