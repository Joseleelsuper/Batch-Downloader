import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { loadRuntimeLocale } from './services/i18n';

function bootstrap() {
  const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
  const render = () => root.render(
    <React.StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </React.StrictMode>,
  );

  // El catálogo español empaquetado permite pintar la aplicación sin esperar a la red.
  // loadRuntimeLocale aplica primero la copia cacheada de forma síncrona y actualiza
  // el árbol cuando termina la revalidación HTTP.
  const localeReady = loadRuntimeLocale();
  render();
  void localeReady.then(render);
}

bootstrap();
