import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useAdminAppsActivity } from './useAdminAppsActivity';

describe('useAdminAppsActivity', () => {
  it('mantiene los mensajes y errores en un único reducer', () => {
    const { result } = renderHook(() => useAdminAppsActivity());

    act(() => {
      result.current.setMessage('Guardado');
      result.current.setError('Falló');
    });
    expect(result.current.message).toBe('Guardado');
    expect(result.current.error).toBe('Falló');

    act(() => {
      result.current.setMessage(null);
      result.current.setError(null);
    });
    expect(result.current.message).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('actualiza cada operación sin alterar las demás', () => {
    const { result } = renderHook(() => useAdminAppsActivity());
    const setters = [
      result.current.setSaving,
      result.current.setInspecting,
      result.current.setDiscoveringWebsite,
      result.current.setApplying,
      result.current.setGeneratingDescription,
      result.current.setDeletingSelected,
      result.current.setExportingCsv,
      result.current.setDeletingAll,
      result.current.setRetryingSelected,
    ];

    setters.forEach((setOperation) => {
      act(() => setOperation(true));
    });

    expect(result.current).toMatchObject({
      saving: true,
      inspecting: true,
      discoveringWebsite: true,
      applying: true,
      generatingDescription: true,
      deletingSelected: true,
      exportingCsv: true,
      deletingAll: true,
      retryingSelected: true,
    });

    act(() => result.current.setApplying(false));
    expect(result.current.applying).toBe(false);
    expect(result.current.saving).toBe(true);
  });
});
