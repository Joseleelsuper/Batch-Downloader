import { lazy, type ComponentType, type LazyExoticComponent } from 'react';

/** Carga una exportación nombrada como componente de ruta diferido. */
export function lazyNamed<
  Module extends Record<string, unknown>,
  Name extends keyof Module,
>(
  loader: () => Promise<Module>,
  name: Name,
): LazyExoticComponent<ComponentType> {
  return lazy(async () => ({ default: component(await loader(), name) }));
}

function component<
  Module extends Record<string, unknown>,
  Name extends keyof Module,
>(module: Module, name: Name): ComponentType {
  return module[name] as ComponentType;
}
