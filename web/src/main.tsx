import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { PromptProvider, ToastProvider } from './ui';
import { applyTheme, savedTheme } from './theme';
import './styles.css';

applyTheme(savedTheme());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ToastProvider>
      <PromptProvider>
        <App />
      </PromptProvider>
    </ToastProvider>
  </StrictMode>,
);
