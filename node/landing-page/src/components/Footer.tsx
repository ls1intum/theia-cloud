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
    
    <p>
      Having problems? Please{' '}
      <a target='_blank' href='https://github.com/eclipse-theia/theia-cloud/issues' rel='noreferrer'>
        report an issue
      </a>
      .

      <div className='App__footer__legal'>
      <a href='https://ase-website-test.ase.cit.tum.de/' target='_blank' rel='noreferrer' className='App__footer__link'>Imprint</a>
      <span className='App__footer__separator'>|</span>
      <a href='/privacy' className='App__footer__link'>Privacy</a>
    </div>
    </p>
  </div>
);
