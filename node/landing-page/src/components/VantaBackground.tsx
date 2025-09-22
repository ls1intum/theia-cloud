import { useEffect, useRef, useState } from 'react';

interface VantaBackgroundProps {
  children: React.ReactNode;
}

declare global {
  interface Window {
    THREE: any;
    VANTA: any;
  }
}

export const VantaBackground: React.FC<VantaBackgroundProps> = ({ children }) => {
  const vantaRef = useRef<HTMLDivElement>(null);
  const [vantaEffect, setVantaEffect] = useState<any>(null);

  useEffect(() => {
    const initializeVanta = () => {
      // Check if VANTA and THREE are loaded globally
      if (window.VANTA && window.THREE && vantaRef.current && !vantaEffect) {
        console.log('Initializing Vanta Birds effect...');
        
        try {
          const effect = window.VANTA.BIRDS({
            el: vantaRef.current,
            mouseControls: false,
            touchControls: false,
            gyroControls: false,
            minHeight: 200.00,
            minWidth: 200.00,
            scale: 1.00,
            scaleMobile: 1.00,
            backgroundColor: 0x2a2a40,
            color1: 0x667eea,
            color2: 0x764ba2,
            birdSize: 1.50,
            wingSpan: 20.00,
            speedLimit: 3.00,
            separation: 40.00,
            alignment: 35.00,
            cohesion: 45.00,
            quantity: 2.00
          });
          
          if (effect) {
            console.log('Vanta Birds effect initialized successfully');
            setVantaEffect(effect);
          }
        } catch (error) {
          console.error('Failed to initialize Vanta effect:', error);
          // Fallback: set background to match VantaJS backgroundColor
          if (vantaRef.current) {
            vantaRef.current.style.background = '#2a2a40';
          }
        }
      } else if (!window.VANTA || !window.THREE) {
        console.log('VANTA or THREE not loaded yet, retrying...');
        // Retry after a short delay
        setTimeout(initializeVanta, 500);
      }
    };

    // Add a small delay to ensure the scripts are loaded
    const timer = setTimeout(initializeVanta, 100);

    return () => {
      clearTimeout(timer);
      if (vantaEffect) {
        console.log('Destroying Vanta effect...');
        vantaEffect.destroy();
      }
    };
  }, [])

  // Handle window resize
  useEffect(() => {
    const handleResize = () => {
      if (vantaEffect) {
        vantaEffect.resize();
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [vantaEffect]);

  return (
    <div style={{ position: 'relative', width: '100%', minHeight: '100vh' }}>
      <div
        ref={vantaRef}
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          width: '100%',
          height: '100%',
          zIndex: -1,
          background: '#2a2a40', // Fallback background matching VantaJS backgroundColor
        }}
      />
      {children}
    </div>
  );
};
