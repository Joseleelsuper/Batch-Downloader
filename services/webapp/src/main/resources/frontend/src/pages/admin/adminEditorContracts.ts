import type { Dispatch, SetStateAction } from 'react';
import type { EditorState, useAdminAppEditor } from './useAdminAppEditor';

type Editor = ReturnType<typeof useAdminAppEditor>;
type EditorAction = Parameters<Editor['dispatchEditor']>[0];

/** Cada consumidor declara los datos, campos editables y transiciones que necesita. */
export type EditorView<Keys extends Exclude<keyof Editor, 'setEditor' | 'dispatchEditor'>> = Pick<Editor, Keys>;
export type EditorUpdates<Keys extends keyof EditorState> = {
  setEditor: <Field extends Keys>(key: Field, value: SetStateAction<EditorState[Field]>) => void;
};
export type EditorEvents<Kind extends EditorAction['type']> = {
  dispatchEditor: Dispatch<Extract<EditorAction, { type: Kind }>>;
};
