/// Resolves saved geometry before choosing a display-relative default layout.
({List<int>? bounds, String? position}) workspaceAppPlacement(
  int index,
  int appCount, {
  Object? savedBounds,
  Object? savedPosition,
}) {
  if (savedBounds is List &&
      savedBounds.length == 4 &&
      savedBounds.every((value) => value is num && value.isFinite)) {
    final bounds = savedBounds.map((value) => (value as num).toInt()).toList();
    if (bounds[2] > bounds[0] && bounds[3] > bounds[1]) {
      return (bounds: bounds, position: null);
    }
  }
  if (savedPosition is String && savedPosition.isNotEmpty) {
    return (bounds: null, position: savedPosition);
  }
  if (appCount <= 1) return (bounds: null, position: null);
  const halves = ['half_left', 'half_right'];
  const grid = [
    'grid_top_left',
    'grid_top_right',
    'grid_bottom_left',
    'grid_bottom_right',
  ];
  final position = appCount == 2 ? halves[index % 2] : grid[index % 4];
  return (bounds: null, position: position);
}
