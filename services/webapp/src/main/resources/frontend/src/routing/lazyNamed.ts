import { lazy, type ComponentType, type LazyExoticComponent } from 'react';

/** Carga una exportación nombrada como componente de ruta diferido. */
export function lazyNamed<
  Module extends Record<string, unknown>,
  Name extends keyof Module,
>(
  loader: () => Promise<Module>,
  name: Name,
): LazyExoticComponent<Extract<Module[Name], ComponentType<any>>> {
  return lazy(async () => ({ default: component(await loader(), name) }));
}

function component<
  Module extends Record<string, unknown>,
  Name extends keyof Module,
>(module: Module, name: Name): Extract<Module[Name], ComponentType<any>> {
  return module[name] as Extract<Module[Name], ComponentType<any>>;
}
