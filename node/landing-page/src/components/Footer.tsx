interface FooterProps {
  selectedAppDefinition: string;
  onNavigate?: (page: 'home' | 'imprint' | 'privacy') => void;
}

export const Footer = ({ selectedAppDefinition, onNavigate }: FooterProps): JSX.Element => {
  const handleNavigation = (page: 'home' | 'imprint' | 'privacy') => {
    if (onNavigate) {
      onNavigate(page);
    } else {
      // Fallback to URL navigation
      window.location.href = page === 'home' ? '/' : `/${page}`;
    }
  };

  return (
  <div className='App__footer'>
    { selectedAppDefinition != '' && (
      <p>
        <label htmlFor='selectapp'> {selectedAppDefinition} </label>
      </p>)
     }
    
    <div className='App__footer__content'>
      <div className='App__footer__attribution'>
        <span>Built by</span> 👨‍💻 <a href='https://github.com/eclipse-theia/theia-cloud' target='_blank' rel='noreferrer'>Theia Cloud Team</a>{' '}
        <span>v1.0.0</span>
      </div>
      
      <div className='App__footer__issues'>
        <a target='_blank' href='https://github.com/eclipse-theia/theia-cloud/issues' rel='noreferrer'>🐞 Report a bug</a> or <a target='_blank' href='https://github.com/eclipse-theia/theia-cloud/issues' rel='noreferrer'>💡 Request a feature</a>
      </div>
      
      <div className='App__footer__legal'>
        <a href='https://ase-website-test.ase.cit.tum.de/' target='_blank' rel='noreferrer' className='App__footer__link'>About</a>
        <span className='App__footer__separator'>|</span>
        <button onClick={() => handleNavigation('imprint')} className='App__footer__link App__footer__button'>Imprint</button>
        <span className='App__footer__separator'>|</span>
        <button onClick={() => handleNavigation('privacy')} className='App__footer__link App__footer__button'>Privacy</button>
      </div>
    </div>
  </div>
  );
};
