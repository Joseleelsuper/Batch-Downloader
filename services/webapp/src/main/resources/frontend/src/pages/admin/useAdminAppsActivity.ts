import { useCallback, useReducer } from 'react';

export type ActivityKey =
  | 'saving'
  | 'inspecting'
  | 'discoveringWebsite'
  | 'applying'
  | 'generatingDescription'
  | 'deletingSelected'
  | 'exportingCsv'
  | 'deletingAll'
  | 'retryingSelected';

interface ActivityState extends Record<ActivityKey, boolean> {
  message: string | null;
  error: string | null;
}

type ActivityAction =
  | { type: 'operation'; key: ActivityKey; value: boolean }
  | { type: 'message'; value: string | null }
  | { type: 'error'; value: string | null };

const INITIAL_ACTIVITY: ActivityState = {
  message: null,
  error: null,
  saving: false,
  inspecting: false,
  discoveringWebsite: false,
  applying: false,
  generatingDescription: false,
  deletingSelected: false,
  exportingCsv: false,
  deletingAll: false,
  retryingSelected: false,
};

function activityReducer(state: ActivityState, action: ActivityAction): ActivityState {
  switch (action.type) {
    case 'operation':
      return { ...state, [action.key]: action.value };
    case 'message':
      return { ...state, message: action.value };
    case 'error':
      return { ...state, error: action.value };
  }
}

/** Mantiene indicadores independientes para permitir operaciones simultáneas y avisos del editor. */
export function useAdminAppsActivity() {
  const [state, dispatch] = useReducer(activityReducer, INITIAL_ACTIVITY);
  const setMessage = useCallback((value: string | null) => {
    dispatch({ type: 'message', value });
  }, []);
  const setError = useCallback((value: string | null) => {
    dispatch({ type: 'error', value });
  }, []);
  const setOperation = useCallback((key: ActivityKey, value: boolean) => {
    dispatch({ type: 'operation', key, value });
  }, []);

  return {
    ...state,
    setMessage,
    setError,
    setOperation,
  };
}
