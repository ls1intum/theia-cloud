interface FooterProps {
  selectedAppDefinition: string;
}

export const Footer = ({ selectedAppDefinition }: FooterProps): JSX.Element => (
  <div className='App__footer'>
    { selectedAppDefinition != '' && (
      <p>
        <label htmlFor='selectapp'> {selectedAppDefinition} </label>
      </p>)
     }
    
    <div className='App__footer__content'>
      <div className='App__footer__attribution'>
        <span>Built by</span> 👨‍💻 <a href='https://github.com/eclipse-theia/theia-cloud' target='_blank' rel='noreferrer'>Theia Cloud Team</a>
      </div>
      
      <div className='App__footer__legal'>
        <a href='https://ase-website-test.ase.cit.tum.de/' target='_blank' rel='noreferrer' className='App__footer__link'>About</a>
        <span className='App__footer__separator'>|</span>
        <a href='https://ase-website-test.ase.cit.tum.de/' target='_blank' rel='noreferrer' className='App__footer__link'>Imprint</a>
        <span className='App__footer__separator'>|</span>
        <a href='/privacy' className='App__footer__link'>Privacy</a>
      </div>
      
      <div className='App__footer__version'>
        <span>v1.0.0</span> • <span>commit: abc1234</span>
      </div>
      
      <div className='App__footer__issues'>
        Having problems? <a target='_blank' href='https://github.com/eclipse-theia/theia-cloud/issues' rel='noreferrer'>🐞 Report a bug</a> or <a target='_blank' href='https://github.com/eclipse-theia/theia-cloud/issues' rel='noreferrer'>💡 Request a feature</a>
      </div>
    </div>
  </div>
);
